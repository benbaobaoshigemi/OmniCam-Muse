// Natural photo mode, native half (settings and files: NaturalMode.java).
//
//  * The stock tuning json read by the hybridraw algorithms is replaced by the edited copy the
//    settings app keeps under files/jiege/natural, when there is one.
//  * The eps of the tone transfer that puts the SDR result on the full-resolution image is taken
//    from files/jiege/natural/soften, by rewriting the two instructions that load it.
//
// Both are applied when the algorithms open the tuning json, which happens once per photo,
// before that photo's tone transfer runs.
//
// The eps instructions are found, not assumed: the function is looked up by its exported name in
// the library file, then the "mov / movk" pair that builds the stock eps is searched inside its
// body. Anything that does not match leaves the library untouched.
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <elf.h>
#include <link.h>
#include <jni.h>
#include <mutex>
#include <pthread.h>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "NaturalMode";
constexpr char kRoot[] = "/data/user/0/com.oplus.camera/files/jiege/natural";
constexpr char kStockJson[] = "/odm/etc/camera/conf_tuning_params.json";

constexpr char kLibraryName[] = "libOPAlgoCamHDRTransformCamera.so";
// TmcDownTransModule::mergedGdfUpsampleAndCalOutput(...): the parameter types are left out so a
// changed signature does not matter.
constexpr char kFunctionPrefix[] = "_ZN18TmcDownTransModule29mergedGdfUpsampleAndCalOutput";

// The guided filter eps is built in a register with "mov wN,#lo16; movk wN,#hi16,lsl #16".
// Stock eps is 1.0e-7 = 0x33d6bf95.
constexpr uint32_t kStockLow = 0xbf95;
constexpr uint32_t kStockHigh = 0x33d6;
constexpr uint32_t kMovBase = 0x52800000;   // mov  wN, #imm16
constexpr uint32_t kMovkBase = 0x72a00000;  // movk wN, #imm16, lsl #16
constexpr uint32_t kRegisterMask = 0x1f;
constexpr uint32_t kImmediateMask = 0xffff << 5;
constexpr size_t kPairWindow = 16;          // instructions between the mov and the movk

struct Library {
    uintptr_t base = 0;
    std::string path;
};

struct EpsSite {
    uint32_t *mov = nullptr;
    uint32_t *movk = nullptr;
    uint32_t reg = 0;
};

Library hdrTransform;
EpsSite site;
bool siteSearched;
std::atomic<bool> softenMode{false};
std::mutex epsMutex;
char appliedEps[24] = "stock";  // for the status file
bool lastServed;                // whether the last tuning json read got the edited copy

int findLibrary(dl_phdr_info *info, size_t, void *result) {
    if (info->dlpi_name && std::strstr(info->dlpi_name, kLibraryName)) {
        auto *library = static_cast<Library *>(result);
        library->base = info->dlpi_addr;
        library->path = info->dlpi_name;
    }
    return 0;
}

bool readAt(FILE *file, uint64_t offset, void *out, size_t size) {
    return std::fseek(file, static_cast<long>(offset), SEEK_SET) == 0 && std::fread(out, 1, size, file) == size;
}

// Address range (load-bias relative) of the first dynamic symbol whose name starts with prefix.
bool findFunction(const std::string &path, const char *prefix, uint64_t &start, uint64_t &size) {
    FILE *file = std::fopen(path.c_str(), "rbe");
    if (!file) return false;
    bool found = false;
    Elf64_Ehdr header;
    std::vector<Elf64_Shdr> sections;
    if (readAt(file, 0, &header, sizeof(header)) && header.e_shnum > 0 && header.e_shentsize == sizeof(Elf64_Shdr)) {
        sections.resize(header.e_shnum);
        if (!readAt(file, header.e_shoff, sections.data(), sections.size() * sizeof(Elf64_Shdr))) sections.clear();
    }
    for (const Elf64_Shdr &dynsym : sections) {
        if (dynsym.sh_type != SHT_DYNSYM || dynsym.sh_link >= sections.size()) continue;
        const Elf64_Shdr &strtab = sections[dynsym.sh_link];
        std::vector<Elf64_Sym> symbols(dynsym.sh_size / sizeof(Elf64_Sym));
        std::vector<char> names(strtab.sh_size);
        if (!readAt(file, dynsym.sh_offset, symbols.data(), symbols.size() * sizeof(Elf64_Sym)) ||
            !readAt(file, strtab.sh_offset, names.data(), names.size())) {
            break;
        }
        const size_t prefixLength = std::strlen(prefix);
        for (const Elf64_Sym &symbol : symbols) {
            if (ELF64_ST_TYPE(symbol.st_info) != STT_FUNC || symbol.st_size == 0 || symbol.st_name >= names.size()) continue;
            if (std::strncmp(&names[symbol.st_name], prefix, prefixLength) == 0) {
                start = symbol.st_value;
                size = symbol.st_size;
                found = true;
                break;
            }
        }
        break;
    }
    std::fclose(file);
    return found;
}

// The one "mov wN,#stock lo16 ... movk wN,#stock hi16,lsl #16" pair inside the function.
bool findEpsSite(EpsSite &out) {
    uint64_t start = 0, size = 0;
    if (!findFunction(hdrTransform.path, kFunctionPrefix, start, size)) return false;
    auto *code = reinterpret_cast<uint32_t *>(hdrTransform.base + start);
    const size_t count = size / sizeof(uint32_t);
    int matches = 0;
    for (size_t i = 0; i < count; i++) {
        if ((code[i] & ~(kImmediateMask | kRegisterMask)) != kMovBase || ((code[i] >> 5) & 0xffff) != kStockLow) continue;
        const uint32_t reg = code[i] & kRegisterMask;
        for (size_t j = i + 1; j < count && j <= i + kPairWindow; j++) {
            if (code[j] == (kMovkBase | (kStockHigh << 5) | reg)) {
                out = {&code[i], &code[j], reg};
                matches++;
                break;
            }
        }
    }
    return matches == 1;
}

// eps wanted by the settings; 0 = stock.
float wantedEps() {
    if (!softenMode.load()) return 0;
    char path[128];
    std::snprintf(path, sizeof(path), "%s/soften", kRoot);
    FILE *file = std::fopen(path, "re");
    if (!file) return 0;
    float eps = 0;
    if (std::fscanf(file, "%f", &eps) != 1 || !(eps > 0 && eps < 1)) eps = 0;
    std::fclose(file);
    return eps;
}

void writeStatus();

bool locateSite() {
    if (site.mov) return true;
    if (siteSearched) return false;
    if (!hdrTransform.base) dl_iterate_phdr(findLibrary, &hdrTransform);
    if (!hdrTransform.base) return false;  // not loaded yet: search again later
    siteSearched = true;
    if (!findEpsSite(site)) {
        site = {};
        __android_log_print(ANDROID_LOG_ERROR, kTag, "eps instructions not found, library left as is");
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "eps instructions at +0x%lx / +0x%lx (w%u)",
                        static_cast<unsigned long>(reinterpret_cast<uintptr_t>(site.mov) - hdrTransform.base),
                        static_cast<unsigned long>(reinterpret_cast<uintptr_t>(site.movk) - hdrTransform.base), site.reg);
    return true;
}

void applyEps() {
    std::lock_guard<std::mutex> lock(epsMutex);
    if (!locateSite()) return;
    uint32_t wantLow = kStockLow, wantHigh = kStockHigh;
    if (float eps = wantedEps(); eps > 0) {
        uint32_t bits;
        std::memcpy(&bits, &eps, sizeof(bits));
        wantLow = bits & 0xffff;
        wantHigh = bits >> 16;
    }
    const uint32_t wantMov = kMovBase | (wantLow << 5) | site.reg;
    const uint32_t wantMovk = kMovkBase | (wantHigh << 5) | site.reg;
    if (*site.mov == wantMov && *site.movk == wantMovk) {
        writeStatus();
        return;
    }

    const uintptr_t page = reinterpret_cast<uintptr_t>(site.mov) & ~uintptr_t{4095};
    const size_t length = ((reinterpret_cast<uintptr_t>(site.movk) + 4 + 4095) & ~uintptr_t{4095}) - page;
    if (mprotect(reinterpret_cast<void *>(page), length, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "eps: mprotect failed");
        return;
    }
    *site.mov = wantMov;
    *site.movk = wantMovk;
    __builtin___clear_cache(reinterpret_cast<char *>(site.mov), reinterpret_cast<char *>(site.movk) + 4);
    mprotect(reinterpret_cast<void *>(page), length, PROT_READ | PROT_EXEC);
    std::snprintf(appliedEps, sizeof(appliedEps), "%04x:%04x", wantHigh, wantLow);
    __android_log_print(ANDROID_LOG_INFO, kTag, "eps immediates %s", appliedEps);
    writeStatus();
}

// One line describing the last tuning json read, for checking from outside (android logs of this
// process are not always visible).
void writeStatus() {
    char path[128];
    std::snprintf(path, sizeof(path), "%s/status", kRoot);
    FILE *file = std::fopen(path, "we");
    if (!file) return;
    std::fprintf(file, "tuning json: %s\neps: %s%s\n", lastServed ? "edited copy" : "stock", appliedEps,
                 site.mov ? "" : " (eps site not located)");
    std::fclose(file);
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_local_jiege_hook_natural_NaturalMode_setSoftenMode(JNIEnv *, jclass, jboolean enabled) {
    softenMode.store(enabled == JNI_TRUE);
    applyEps();
}

// LSPosed calls this while dlopen still holds the linker lock. Run the patch after returning to
// the loader, so locateSite's dl_iterate_phdr cannot wait for that lock while another capture
// thread holds epsMutex.
void *applyEpsAfterLoad(void *) {
    applyEps();
    return nullptr;
}

void onLibraryLoadedForNatural(const char *name) {
    if (!name || !std::strstr(name, kLibraryName)) return;
    pthread_t worker;
    if (pthread_create(&worker, nullptr, applyEpsAfterLoad, nullptr) == 0) {
        pthread_detach(worker);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "eps patch worker could not start");
    }
}

const char *naturalRemapPath(const char *path, char *buf, size_t n) {
    if (std::strcmp(path, kStockJson) != 0) return nullptr;
    applyEps();
    std::snprintf(buf, n, "%s/conf_tuning_params.json", kRoot);
    lastServed = access(buf, R_OK) == 0;
    writeStatus();
    return lastServed ? buf : nullptr;
}
