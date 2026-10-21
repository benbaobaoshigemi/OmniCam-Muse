// Master mode: live master-colour look (HNCS, Lumix, film, Canon, Darktable, Leica M9) in place of the stock master LMT.
//
// Capture (BasicTone, gr_basictone.cpp): getLmtParams reads the frame's CCT, CCM and luxindex;
// processCore picks the path from the input size (thumbnail 1280x960 vs full); loadLut points the
// LMT directory at files/jiege/mastercolor/live/<key>, baked here on first use (master_core.cpp).
// Preview (CombineLut, MasterColor.java): the Java side writes live/<dir>/key.bin and points the
// LMT directory there; the libc path hooks bake it on the first LMTLut read.
// In a live directory LMTLut1..5 are served as LMTLut0 (the stock luma grid then interpolates
// between identical tables) unless the directory holds its own (blend mode, x8u), and every other name
// as the stock ODM lmt entry (SC / CWCM stay stock).
//
// files/jiege/mastercolor/look (written by MasterColor.java from the 大师色彩 setting) selects the look:
// "oppo" keeps the stock master LMT; "hncs", "lumix_<style>" or "film_<film>" (looks/<name>.bin).
// A missing file means HNCS, the default. Blend mode adds the tone and colour weights (percent) on
// lines 3 and 4: the directory then holds all six LMTLut0..5, each the stock table blended with the look.
#include <android/log.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadataTags.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

#include "master_core.h"
#include "master_camera_id.h"
#include "native_hooks.h"

void *gAlgoInterfaceHandle = nullptr;

namespace {

constexpr char kTag[] = "MasterColor";
constexpr char kRoot[] = "/data/user/0/com.oplus.camera/files/jiege/mastercolor";
constexpr char kLive[] = "/data/user/0/com.oplus.camera/files/jiege/mastercolor/live/";
constexpr char kStockLmt[] = "/odm/etc/camera/basictone/lmt/";
constexpr int kKeepDirs = 48;
constexpr int kPruneBatch = 32;
constexpr long long kKeepBytes = 192ll << 20;  // live directories are pruned past this much disk (blend tables are 19 MB each)
constexpr size_t kRecent = 8;                  // the directories most recently handed to the camera are never pruned

thread_local bool inMaster = false;  // bake I/O re-enters the libc path hooks
std::mutex bakeMutex;

bool exists(const std::string &path) {
    struct stat st{};
    return ::stat(path.c_str(), &st) == 0;
}

// Existence check that bypasses libc: the path hooks (pop_path_remap.cpp) call masterColorRemapPath from
// stat / access / open, so anything it uses must not go back through them (stat here recursed forever).
bool existsRaw(const char *path) {
    return ::syscall(__NR_faccessat, AT_FDCWD, path, F_OK) == 0;
}

bool engine() {
    if (mastercolor::engineReady()) return true;
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [] {
        std::string error;
        ok = mastercolor::loadEngine(std::string(kRoot) + "/hncs_engine.bin", &error);
        if (ok) __android_log_print(ANDROID_LOG_INFO, kTag, "engine ready");
        else __android_log_print(ANDROID_LOG_ERROR, kTag, "engine unavailable: %s", error.c_str());
    });
    return ok;
}

void removeTree(const std::string &dir) {
    if (DIR *d = ::opendir(dir.c_str())) {
        while (dirent *e = ::readdir(d)) {
            if (!std::strcmp(e->d_name, ".") || !std::strcmp(e->d_name, "..")) continue;
            ::unlink((dir + "/" + e->d_name).c_str());
        }
        ::closedir(d);
    }
    ::rmdir(dir.c_str());
}

// Directories the camera was last pointed at (preview and capture): in use, whatever their age.
std::mutex recentMutex;
std::vector<std::string> recentDirs;

void markUsed(const std::string &dir) {
    std::lock_guard<std::mutex> lock(recentMutex);
    auto found = std::find(recentDirs.begin(), recentDirs.end(), dir);
    if (found != recentDirs.end()) recentDirs.erase(found);
    recentDirs.push_back(dir);
    if (recentDirs.size() > kRecent) recentDirs.erase(recentDirs.begin());
}

bool recentlyUsed(const std::string &dir) {
    std::lock_guard<std::mutex> lock(recentMutex);
    return std::find(recentDirs.begin(), recentDirs.end(), dir) != recentDirs.end();
}

// Keep recent tables and keys; an unbaked key may still be queued for preview loading. Oldest first once there are
// more than kKeepDirs directories or more than kKeepBytes on disk; directories in use and those younger than a
// minute stay.
void pruneNow() {
    struct Entry { long time; long long bytes; std::string path; };
    std::vector<Entry> dirs;
    const long now = ::time(nullptr);
    long long total = 0;
    if (DIR *d = ::opendir(kLive)) {
        while (dirent *e = ::readdir(d)) {
            if (e->d_name[0] == '.') continue;
            std::string path = std::string(kLive) + e->d_name;
            struct stat table{}, key{};
            bool haveTable = ::stat((path + "/LMTLut0").c_str(), &table) == 0;
            bool haveKey = ::stat((path + "/key.bin").c_str(), &key) == 0;
            if (!haveTable && !haveKey) continue;
            long long bytes = haveTable ? table.st_size : 0;
            struct stat more{};
            if (haveTable && ::stat((path + "/LMTLut1").c_str(), &more) == 0) bytes *= 6;   // blend mode: LMTLut0..5
            long time = std::max(haveTable ? long(table.st_mtime) : 0L, haveKey ? long(key.st_mtime) : 0L);
            dirs.push_back({time, bytes, path});
            total += bytes;
        }
        ::closedir(d);
    }
    if (dirs.size() <= size_t(kKeepDirs) && total <= kKeepBytes) return;
    std::sort(dirs.begin(), dirs.end(), [](const Entry &a, const Entry &b) { return a.time < b.time; });
    size_t count = dirs.size();
    int removed = 0;
    for (const Entry &entry : dirs) {
        if ((count <= size_t(kKeepDirs) && total <= kKeepBytes) || removed >= kPruneBatch) break;
        if (now - entry.time <= 60 || recentlyUsed(entry.path)) continue;
        removeTree(entry.path);
        total -= entry.bytes;
        --count;
        ++removed;
    }
}

// Pruning deletes up to a few dozen files: not on a camera thread, never two at once.
void prune() {
    static std::atomic_flag running = ATOMIC_FLAG_INIT;
    if (running.test_and_set()) return;
    std::thread([] {
        inMaster = true;   // its stat calls go through the path hooks
        pruneNow();
        running.clear();
    }).detach();
}

bool writeFile(const std::string &path, const std::vector<float> &lut) {
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
    bool ok = fd >= 0;
    const char *bytes = reinterpret_cast<const char *>(lut.data());
    size_t left = lut.size() * sizeof(float);
    while (ok && left > 0) {
        ssize_t n = ::write(fd, bytes, left);
        if (n <= 0) ok = false; else { bytes += n; left -= size_t(n); }
    }
    if (fd >= 0) ::close(fd);
    return ok;
}

// Writes LMTLut0 (replacement) or LMTLut0..5 (blend, or a look with slot tables); LMTLut0 is renamed last, so its presence means the
// directory is complete.
bool writeTables(const std::string &dir, const std::vector<std::vector<float>> &tables) {
    std::string tmp = dir + ".tmp" + std::to_string(::gettid());
    removeTree(tmp);
    if (::mkdir(tmp.c_str(), 0770) != 0) return false;
    bool ok = true;
    for (size_t i = 0; ok && i < tables.size(); i++) ok = writeFile(tmp + "/LMTLut" + std::to_string(i), tables[i]);
    if (ok) {
        if (::mkdir(dir.c_str(), 0770) != 0 && !exists(dir)) ok = false;
        for (size_t i = tables.size(); ok && i-- > 0;) {
            std::string name = "/LMTLut" + std::to_string(i);
            ok = ::rename((tmp + name).c_str(), (dir + name).c_str()) == 0;
        }
    }
    removeTree(tmp);
    return ok;
}

// Bakes dir/LMTLut0 for key unless present. Caller holds no lock.
bool ensure(const std::string &dir, const mastercolor::Key &key) {
    markUsed(dir);
    if (exists(dir + "/LMTLut0")) return true;
    if (!engine()) return false;
    std::unique_lock<std::mutex> lock(bakeMutex);
    if (exists(dir + "/LMTLut0")) return true;
    ::mkdir(kRoot, 0770);
    ::mkdir(kLive, 0770);
    auto start = std::chrono::steady_clock::now();
    unsigned threads = std::max(2u, std::min(8u, std::thread::hardware_concurrency()));
    std::vector<std::vector<float>> looks, tables;
    bool ok = mastercolor::lookTables(key, looks, int(threads));
    if (ok && key.blended()) ok = mastercolor::blendWithStock(key, looks, kStockLmt, tables, int(threads));
    else if (ok) tables = std::move(looks);
    ok = ok && writeTables(dir, tables);
    long ms = long(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
    if (ok) {
        size_t mid = (size_t(32) * mastercolor::kLutSize + 32) * mastercolor::kLutSize + 32;
        __android_log_print(ANDROID_LOG_INFO, kTag, "baked %s in %ld ms (node 0.5 -> %.4f, %zu table%s, tone %.2f colour %.2f)", dir.c_str(), ms,
                            tables[0][mid], tables.size(), tables.size() == 1 ? "" : "s", key.toneWeight, key.colourWeight);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "bake FAILED for %s", dir.c_str());
    }
    lock.unlock();
    if (ok) prune();
    return ok;
}

// ---------------------------------------------------------------- frame metadata
struct RoEntry {
    size_t index;
    uint32_t tag;
    uint8_t type;
    size_t count;
    const void *data;
};
using FindEntry = int (*)(const void *, uint32_t, RoEntry *);

std::string readLibcxxString(const uint8_t *s) {
    if ((s[0] & 1) == 0) return std::string(reinterpret_cast<const char *>(s + 1), s[0] >> 1);
    size_t size;
    const char *data;
    std::memcpy(&size, s + 8, 8);
    std::memcpy(&data, s + 16, 8);
    return size < 4096 && data ? std::string(data, size) : std::string();
}

// libc++ std::map<std::string, unsigned> in-order walk: node {left, right, parent, color, pair}.
void readTagNames(const void *mapObject, std::unordered_map<std::string, uint32_t> &out) {
    if (!mapObject) return;
    const uint8_t *m = static_cast<const uint8_t *>(mapObject);
    const uint8_t *end = m + 8;
    const uint8_t *node;
    size_t size;
    std::memcpy(&node, m, 8);
    std::memcpy(&size, m + 16, 8);
    for (size_t guard = 0; node && node != end && guard < size + 1 && guard < 20000; guard++) {
        uint32_t tag;
        std::memcpy(&tag, node + 32 + 24, 4);
        out.emplace(readLibcxxString(node + 32), tag);
        const uint8_t *right;
        std::memcpy(&right, node + 8, 8);
        if (right) {
            node = right;
            for (const uint8_t *left; std::memcpy(&left, node, 8), left;) node = left;
        } else {
            for (;;) {
                const uint8_t *parent, *parentLeft;
                std::memcpy(&parent, node + 16, 8);
                std::memcpy(&parentLeft, parent, 8);
                if (parentLeft == node) { node = parent; break; }
                node = parent;
                if (node == end) break;
            }
        }
    }
}

struct FrameMeta {
    FindEntry find = nullptr;
    const void *metadata = nullptr;
    const std::unordered_map<std::string, uint32_t> *names = nullptr;

    const RoEntry *get(const char *name, RoEntry &e) const {
        auto it = names->find(name);
        if (it == names->end() || find(metadata, it->second, &e) != 0 || e.count == 0 || !e.data) return nullptr;
        return &e;
    }
};

std::mutex namesMutex;
std::unordered_map<const void *, std::unordered_map<std::string, uint32_t>> namesCache;

bool frameMeta(void *frame, FrameMeta &m) {
    if (!gAlgoInterfaceHandle) return false;
    static FindEntry find = reinterpret_cast<FindEntry>(::dlsym(gAlgoInterfaceHandle, "find_camera_metadata_ro_entry"));
    m.find = find;
    m.metadata = *reinterpret_cast<void **>(static_cast<char *>(frame) + 0xff8);
    const void *names = *reinterpret_cast<void **>(static_cast<char *>(frame) + 0x1180);
    if (!m.find || !m.metadata || !names) return false;
    std::lock_guard<std::mutex> lock(namesMutex);
    auto &map = namesCache[names];
    if (map.empty()) readTagNames(names, map);
    m.names = &map;
    return !map.empty();
}

// The scene front stage profile of a physical camera. Camera2 characteristics are read once per physical ID,
// never per zoom ratio or the logical ID; the lens is named by its 35 mm equivalent focal length.
bool cameraLens(const std::string &id, std::string &lens) {
    static std::mutex mutex;
    static std::unordered_map<std::string, std::string> cache;
    static std::unordered_map<std::string, std::chrono::steady_clock::time_point> failed;   // retried after a pause, not every frame
    static ACameraManager *manager = nullptr;
    std::lock_guard<std::mutex> lock(mutex);
    auto found = cache.find(id);
    if (found != cache.end()) { lens = found->second; return true; }
    auto now = std::chrono::steady_clock::now();
    auto miss = failed.find(id);
    if (miss != failed.end() && now - miss->second < std::chrono::seconds(2)) return false;
    if (!manager) manager = ACameraManager_create();
    if (!manager) return false;
    ACameraMetadata *metadata = nullptr;
    bool ok = ACameraManager_getCameraCharacteristics(manager, id.c_str(), &metadata) == ACAMERA_OK;
    if (ok) {
        ACameraMetadata_const_entry e;
        // A logical camera's characteristics describe only its default physical sensor; the front camera has no profile.
        ok = ACameraMetadata_getConstEntry(metadata, ACAMERA_LOGICAL_MULTI_CAMERA_PHYSICAL_IDS, &e) != ACAMERA_OK &&
             ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_FACING, &e) == ACAMERA_OK && e.count == 1 &&
             e.data.u8[0] == ACAMERA_LENS_FACING_BACK;
        double focal = 0, width = 0, height = 0;
        ok = ok && ACameraMetadata_getConstEntry(metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &e) == ACAMERA_OK &&
             e.count >= 1 && (focal = e.data.f[0]) > 0;
        ok = ok && ACameraMetadata_getConstEntry(metadata, ACAMERA_SENSOR_INFO_PHYSICAL_SIZE, &e) == ACAMERA_OK &&
             e.count == 2 && (width = e.data.f[0]) > 0 && (height = e.data.f[1]) > 0;
        ACameraMetadata_free(metadata);
        ok = ok && mastercolor::sceneLensFor(focal * 43.2666 / std::hypot(width, height), lens);
    }
    if (ok) cache.emplace(id, lens);
    else failed[id] = now;
    return ok;
}

}  // namespace

// look file: line 1 the look name, optional line 2 the exposure compensation in EV, optional lines 3 and 4
// the blend mode's tone and colour weights in percent (missing = 100, plain replacement), line 5 the CMY density
// in percent, optional line 6 the look parameters (up to four numbers, missing or "nan" = the look's default).
bool masterColorLook(std::string &look, float &exposureEv, float &toneWeight, float &colourWeight, std::array<float, 3> &density, std::array<float, 4> &param) {
    look = "hncs";
    exposureEv = 0;
    toneWeight = colourWeight = 1;
    density = {1, 1, 1};
    param = {NAN, NAN, NAN, NAN};
    if (FILE *file = std::fopen((std::string(kRoot) + "/look").c_str(), "re")) {
        char buf[192] = {};
        size_t n = std::fread(buf, 1, sizeof(buf) - 1, file);
        std::fclose(file);
        buf[n] = 0;
        char *second = std::strchr(buf, '\n');
        if (second) {
            *second++ = 0;
            char *third = std::strchr(second, '\n');
            if (third) *third++ = 0;
            float ev = std::strtof(second, nullptr);
            if (std::isfinite(ev) && std::fabs(ev) <= 4) exposureEv = std::round(ev * 100) / 100;
            char *fourth = third ? std::strchr(third, '\n') : nullptr;
            if (fourth) *fourth++ = 0;
            char *fifth = fourth ? std::strchr(fourth, '\n') : nullptr;
            if (fifth) {
                *fifth++ = 0;
                float c, m, y;
                if (std::sscanf(fifth, "%f %f %f", &c, &m, &y) == 3) {
                    const float values[3] = {c, m, y};
                    for (int i = 0; i < 3; ++i)
                        if (std::isfinite(values[i])) density[i] = std::clamp(values[i] / 100.0f, 0.5f, 1.5f);
                }
                if (char *sixth = std::strchr(fifth, '\n')) {
                    char *cursor = sixth + 1;
                    for (float &value : param) {
                        char *end = cursor;
                        float v = std::strtof(cursor, &end);
                        if (end == cursor) break;
                        value = std::isfinite(v) ? v : NAN;
                        cursor = end;
                    }
                }
            }
            auto weight = [](const char *text) {
                float percent = std::strtof(text, nullptr);
                return std::isfinite(percent) ? std::min(std::max(percent, 0.0f), 100.0f) / 100.0f : 1.0f;
            };
            if (third && *third) toneWeight = weight(third);
            if (fourth && *fourth) colourWeight = weight(fourth);
        }
        size_t length = std::strlen(buf);
        while (length > 0 && (buf[length - 1] == ' ' || buf[length - 1] == '\r')) length--;
        if (length > 0) look.assign(buf, length);
    }
    return look != "oppo";
}

// Reads the per-frame inputs of a master capture. False (with a logged reason) keeps it stock.
bool masterColorCaptureInfo(void *frame, int cctFromParams, MasterColorCapture &out) {
    if (!engine()) return false;
    FrameMeta m;
    if (!frameMeta(frame, m)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture: frame metadata unavailable, stock LMT");
        return false;
    }
    RoEntry e{};
    // A per-frame proproc gamma replaces the built-in table: its encoding is not modelled.
    if (m.get("com.oplus.bps.gamma.info", e)) {
        bool nonzero = false;
        for (size_t i = 0; i < e.count && !nonzero; i++) nonzero = static_cast<const int32_t *>(e.data)[i] != 0;
        if (nonzero) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "capture: bps.gamma.info present, stock LMT");
            return false;
        }
    }
    int cct = cctFromParams;
    if (cct < 1500 || cct > 20000) {
        if (m.get("org.quic.camera2.statsconfigs.AWBFrameControlCCT", e)) cct = static_cast<const int32_t *>(e.data)[0];
    }
    if (cct < 1500 || cct > 20000) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture: no CCT, stock LMT");
        return false;
    }
    bool haveCcm = false;
    if (m.get("com.oplus.ofe.ccm", e) && e.count >= 40) {
        const uint8_t *b = static_cast<const uint8_t *>(e.data);
        int32_t enable;
        std::memcpy(&enable, b, 4);
        if (enable == 1) {
            std::memcpy(out.ccm.data(), b + 4, 36);
            haveCcm = true;
        }
    }
    if (!haveCcm && m.find(m.metadata, 0x00000001 /* android.colorCorrection.transform */, &e) == 0 &&
        e.count == 9 && e.type == 5) {
        const int32_t *r = static_cast<const int32_t *>(e.data);
        for (int i = 0; i < 9; i++) out.ccm[i] = r[2 * i + 1] ? float(r[2 * i]) / float(r[2 * i + 1]) : 0.0f;
        haveCcm = true;
    }
    if (!haveCcm) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture: no CCM, stock LMT");
        return false;
    }
    // The frame ID can be the logical camera (0); the result names the sensor used for this frame.
    int32_t frameId;
    std::memcpy(&frameId, static_cast<const char *>(frame) + 0x24, sizeof(frameId));
    const uint8_t *active = nullptr;
    size_t activeCount = 0;
    if (m.find(m.metadata, ACAMERA_LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID, &e) == 0 &&
        e.type == ACAMERA_TYPE_BYTE && e.data) {
        active = static_cast<const uint8_t *>(e.data);
        activeCount = e.count;
    }
    std::string cameraId = masterCameraId(active, activeCount, frameId);
    out.cameraId = cameraId;
    if (!cameraLens(cameraId, out.lens)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "capture: no scene profile for camera %s (frame ID %d)", cameraId.c_str(), frameId);
        return false;
    }
    float lux = -1;
    if (m.get("com.oplus.rawhdr.isp.luxindex", e)) lux = static_cast<const float *>(e.data)[0];
    else if (m.get("com.qti.chi.statsaec.AecLux", e)) lux = static_cast<const float *>(e.data)[0];
    out.cct = cct;
    out.luxIndex = int(lux);  // fcvtzs, as hybridRawGetMetadataV3
    out.luxBlend = out.luxIndex > 280;
    return true;
}

// Returns the baked directory for this capture, or "" to keep the stock LMT.
std::string masterColorCaptureDir(const MasterColorCapture &capture, int width, int height) {
    if (inMaster) return {};
    inMaster = true;
    mastercolor::Key key;
    key.path = (long(width) * height < 4000000) ? mastercolor::Path::Thumbnail : mastercolor::Path::Main;
    key.cct = capture.cct;
    key.ccm = capture.ccm;
    key.lens = capture.lens;
    key.param = capture.param;
    key.luxBlend = key.path == mastercolor::Path::Main && capture.luxBlend;
    key.look = capture.look;
    key.exposureEv = capture.exposureEv;
    key.toneWeight = capture.toneWeight;
    key.colourWeight = capture.colourWeight;
    key.density = capture.density;
    std::string dir = std::string(kLive) + key.name();
    bool ok = ensure(dir, key);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "capture camera=%s lens=%s %dx%d look=%s tone=%.2f colour=%.2f path=%s cct=%d lux=%d blend=%d ccm=[%.4f %.4f %.4f|%.4f %.4f %.4f|%.4f %.4f %.4f] -> %s",
                        capture.cameraId.c_str(), key.lens.c_str(), width, height, key.look.c_str(), key.toneWeight, key.colourWeight, key.path == mastercolor::Path::Main ? "main" : "thumbnail", key.cct, capture.luxIndex,
                        int(key.luxBlend), key.ccm[0], key.ccm[1], key.ccm[2], key.ccm[3], key.ccm[4], key.ccm[5],
                        key.ccm[6], key.ccm[7], key.ccm[8], ok ? dir.c_str() : "STOCK (bake failed)");
    inMaster = false;
    return ok ? dir : std::string();
}

// Path hook helper: bakes a preview directory on first use (its key.bin comes from Java).
void masterColorPreparePath(const char *path) {
    if (inMaster || !path || std::strncmp(path, kLive, sizeof(kLive) - 1) != 0) return;
    const char *rest = path + sizeof(kLive) - 1;
    const char *slash = std::strchr(rest, '/');
    if (!slash || std::strncmp(slash + 1, "LMTLut", 6) != 0) return;
    std::string dir(path, size_t(slash - path));
    inMaster = true;
    markUsed(dir);
    bool baked = exists(dir + "/LMTLut0");
    if (!baked) {
        mastercolor::Key key;
        if (mastercolor::readKeyFile(dir + "/key.bin", key)) ensure(dir, key);
    }
    inMaster = false;
}

// Path hook helper: serves every name inside a live directory. nullptr = leave the path alone.
const char *masterColorRemapPath(const char *path, char *buf, size_t n) {
    if (!path || std::strncmp(path, kLive, sizeof(kLive) - 1) != 0) return nullptr;
    const char *rest = path + sizeof(kLive) - 1;
    const char *slash = std::strchr(rest, '/');
    if (!slash || !slash[1]) return nullptr;
    // A directory being written (dir.tmp<tid>) is not a served directory: its LMTLut1..5 are real files.
    if (std::string_view(rest, size_t(slash - rest)).find(".tmp") != std::string_view::npos) return nullptr;
    const char *name = slash + 1;
    if (!std::strcmp(name, "LMTLut0") || !std::strcmp(name, "key.bin") || std::strstr(name, ".tmp")) return nullptr;
    if (!std::strncmp(name, "LMTLut", 6) && name[6] >= '1' && name[6] <= '9' && name[7] == 0) {
        if (existsRaw(path)) return nullptr;  // blend mode: the six tables are all baked
        std::snprintf(buf, n, "%.*sLMTLut0", int(name - path), path);
    } else {
        std::snprintf(buf, n, "%s%s", kStockLmt, name);
    }
    return buf;
}
