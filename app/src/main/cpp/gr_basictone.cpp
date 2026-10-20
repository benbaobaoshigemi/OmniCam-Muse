// GR port, native half.
//
// The GR mode reuses the professional (master) capture pipeline. GR frames are recognised by the
// gr_effect_size APS setting (63) and get:
//   * BasicTone: the GR LMT/SC/CWCM tree (staged by service.sh) instead of the host ODM tree;
//   * no artistic vignette from the V1 vignette parameters;
//   * the Meishe V1 filter node (clarity, vignette, grain), run with the GR capture mode.
// Master frames (capture mode 33) get the live master-colour LMT instead (master_color.cpp), unless
// files/jiege/mastercolor/off exists.
// Offsets are for PMA110 Camera 7.006.77 (libAlgoInterface.so / libBasicTonePhoto*.so).
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <string>
#include <unordered_map>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "RicohGrPort";
constexpr char kGrLmtPath[] = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";
constexpr char kHostLmtPath[] = "/odm/etc/camera/basictone/lmt";
constexpr uint32_t kGrCaptureMode = 37;
constexpr uint32_t kMasterCaptureMode = 33;

// APS setting keys use libc++'s 24-byte short-string layout on this firmware.
struct alignas(8) SettingKey {
    uint8_t length;
    char text[23];
};
const SettingKey kGrSizeKey = {28, "gr_effect_size"};

// `android::getSettingInt` is not exported, so it is reached by file offset - the ONE raw value in
// this file that changes between firmware builds. The first four instructions of the function are
// used as a fingerprint so a build picks its own offset, and so a wrong table entry fails safe
// (nothing is hooked and the camera stays untouched).
//
//   PMA110 ColorOS / Camera 7.006.77 (and Muse's own backend build)
//       paciasp; sub sp, sp, #0x90; stp x29,x28,[sp,#0x60]; stp x21,x20,[sp,#0x50]
//   PKJ110 ColorOS 17 F.21 / Camera 7.006.94
//       paciasp; sub sp, sp, #0x90; stp x29,x28,[sp,#0x60]; stp x23,x22,[sp,#0x70]
// Both call it from getLmtParamsV1 and getVigParamsV1. Verified by reading the ELF of each build
// (2026-10-17, port-x8u notes).
struct SettingIntLayout {
    const char *build;
    uint8_t prologue[16];
    uintptr_t offset;
};

constexpr SettingIntLayout kSettingIntLayouts[] = {
    {// Find X9 Ultra (PMA110)
     "PMA110/7.006.77",
     {0x3f, 0x23, 0x03, 0xd5, 0xff, 0x43, 0x02, 0xd1,
      0xfd, 0x7b, 0x06, 0xa9, 0xf5, 0x3b, 0x00, 0xf9},
     0x150cdd0},
    {// Find X8 Ultra (PKJ110)
     "PKJ110/7.006.94",
     {0x3f, 0x23, 0x03, 0xd5, 0xff, 0x43, 0x02, 0xd1,
      0xfd, 0x7b, 0x06, 0xa9, 0xf6, 0x57, 0x07, 0xa9},
     0x13f46d4},
};

int (*getSettingInt)(void *settings, const void *key, int fallback);
void (*originalGetLmtParams)(void *, void *, int);
void (*originalGetVigParams)(void *, void *, void *);
int (*originalFilterProcessV2)(void *, int);
int (*filterProcessV1)(void *, int);
int (*filterRegisterV1)(void *, int);
int (*filterUnregisterV1)(void *, int);
uintptr_t (*originalProcessCore)(void *, void *, void *, void *, void *);
uintptr_t (*originalLoadLut)(void *, int);
// Guards hookBasicTone against being run twice on the same library.
//
// The obvious guard - `originalProcessCore && originalLoadLut` - only latches when BOTH symbols
// resolve. X8U's stock libBasicTonePhoto.so exports the classic loadLut(CamMode), but the X9U
// backend (libBasicTonePhotoX10.so, staged as libBasicTonePhoto.so on PKJ110) renamed it to
// loadLut(CamMode, const char*, bool). There originalLoadLut would stay NULL forever, so every
// dlopen("libBasicTonePhoto*.so") re-ran the hook; the second inline hook on an already patched
// processCore fails, and on failure the hook framework clears the backup it was handed - leaving
// originalProcessCore NULL while the first hook's branch is still in place. The next BasicTone
// frame then jumped through that NULL trampoline: signal 11 at fault addr 0x0 in thread
// RefFrameProc (observed 2026-10-14 19:22:45 on PKJ110).
bool basicToneHooked = false;

// getLmtParamsV1 runs per frame before BasicTone; remember which LUT tree belongs to each parameter
// block so processCore/loadLut (which no longer see the frame) can pick it.
enum Profile { kUnknown = -1, kHost = 0, kGr = 1, kMaster = 2 };
struct FrameProfile {
    Profile profile = kHost;
    bool master = false;  // a master capture, whatever LUT tree it uses
    MasterColorCapture capture;
};
std::mutex profileMutex;
std::unordered_map<void *, FrameProfile> frameProfiles;
thread_local int activeProfile = kUnknown;
thread_local bool activeMaster = false;
thread_local std::string activeMasterDir;

bool isGrFrame(void *frame) {
    // A build whose getSettingInt fingerprint did not match has no accessor: there is no way to
    // tell a GR frame apart, so none is claimed (the GR LUT tree is not swapped).
    if (!getSettingInt) return false;
    void *settings = *reinterpret_cast<void **>(static_cast<char *>(frame) + 0x8110);
    return getSettingInt(settings, &kGrSizeKey, 0) == 63;
}

void getLmtParams(void *parameters, void *frame, int stage) {
    bool gr = isGrFrame(frame);
    originalGetLmtParams(parameters, frame, stage);
    FrameProfile profile;
    profile.profile = gr ? kGr : kHost;
    uint32_t mode = *reinterpret_cast<uint32_t *>(static_cast<char *>(frame) + 0x18);
    profile.master = !gr && mode == kMasterCaptureMode;
    std::string look;
    float exposureEv = 0, toneWeight = 1, colourWeight = 1;
    std::array<float, 4> param{NAN, NAN, NAN, NAN};
    std::array<float, 3> density{1, 1, 1};
    if (!gr && mode == kMasterCaptureMode && masterColorLook(look, exposureEv, toneWeight, colourWeight, density, param)) {
        // ProcessParam +0x3c is the frame CCT filled by getLmtParamsV1.
        int cct = *reinterpret_cast<int32_t *>(static_cast<char *>(parameters) + 0x3c);
        if (masterColorCaptureInfo(frame, cct, profile.capture)) {
            profile.capture.look = look;
            profile.capture.exposureEv = exposureEv;
            profile.capture.toneWeight = toneWeight;
            profile.capture.colourWeight = colourWeight;
            profile.capture.density = density;
            profile.capture.param = param;
            profile.profile = kMaster;
        }
    }
    std::lock_guard<std::mutex> lock(profileMutex);
    frameProfiles[parameters] = profile;
}

void getVigParams(void *parameters, void *frame, void *planes) {
    originalGetVigParams(parameters, frame, planes);
    if (isGrFrame(frame)) static_cast<uint8_t *>(parameters)[0x1a] = 0;
}

int withGrCaptureMode(int (*operation)(void *, int), void *frame, int stage) {
    auto *mode = reinterpret_cast<uint32_t *>(static_cast<char *>(frame) + 0x18);
    uint32_t previous = *mode;
    *mode = kGrCaptureMode;
    int result = operation(frame, stage);
    *mode = previous;
    return result;
}

int filterProcessV2(void *frame, int stage) {
    if (!isGrFrame(frame)) return originalFilterProcessV2(frame, stage);
    // Registration happens before gr_effect_size is attached, so the V1 node's whole lifecycle
    // is scoped to this call, where the frame is known to be GR. The per-image object is
    // consumed after capture; session and request modes are untouched.
    int registration = withGrCaptureMode(filterRegisterV1, frame, stage);
    if (registration != 0) return registration;
    int result = withGrCaptureMode(filterProcessV1, frame, stage);
    withGrCaptureMode(filterUnregisterV1, frame, stage);
    return result;
}

uintptr_t processCore(void *object, void *input, void *output, void *parameters, void *metadata) {
    // Defence in depth: never call through a trampoline the hook framework refused to build.
    if (!originalProcessCore) return 0;
    int previous = activeProfile;
    bool previousMaster = activeMaster;
    std::string previousDir = activeMasterDir;
    activeProfile = kUnknown;
    activeMaster = false;
    FrameProfile profile;
    bool known = false;
    {
        std::lock_guard<std::mutex> lock(profileMutex);
        auto found = frameProfiles.find(parameters);
        if (found != frameProfiles.end()) {
            profile = found->second;
            known = true;
            frameProfiles.erase(found);
        }
    }
    if (known) {
        activeProfile = profile.profile;
        activeMaster = profile.master;
        if (profile.profile == kMaster) {
            // BasicTone_OGL::processCore logs Image +0 / +4 as the width and height.
            int width = *static_cast<int32_t *>(input), height = static_cast<int32_t *>(input)[1];
            activeMasterDir = masterColorCaptureDir(profile.capture, width, height);
            if (activeMasterDir.empty()) activeProfile = kHost;
        }
    }
    uintptr_t result = originalProcessCore(object, input, output, parameters, metadata);
    activeProfile = previous;
    activeMaster = previousMaster;
    activeMasterDir = previousDir;
    return result;
}

uintptr_t loadLut(void *object, int mode) {
    if (!originalLoadLut) return 0;
    // Intermediate ODT passes carry no profile; they keep the object's current LUTs.
    if (activeProfile != kUnknown) {
        char *lmtPath = static_cast<char *>(object) + 0x008;  // 256-byte path buffer
        const char *desired = activeProfile == kGr ? kGrLmtPath
                            : activeProfile == kMaster ? activeMasterDir.c_str() : kHostLmtPath;
        if (std::strlen(desired) < 240 && std::strcmp(lmtPath, desired) != 0) {
            std::strcpy(lmtPath, desired);
            // The path is not part of the vendor cache key, so a profile switch forces a reload.
            *reinterpret_cast<int *>(static_cast<char *>(object) + 0xeb0) = -1;
            __android_log_print(ANDROID_LOG_INFO, kTag, "BasicTone profile=%s path=%s",
                                activeProfile == kGr ? "GR" : activeProfile == kMaster ? "master-colour" : "host", desired);
        }
    }
    return originalLoadLut(object, mode);
}

void *resolveSettingInt(void *base) {
    for (const auto &layout : kSettingIntLayouts) {
        auto *candidate = static_cast<char *>(base) + layout.offset;
        if (std::memcmp(candidate, layout.prologue, sizeof(layout.prologue)) == 0) {
            __android_log_print(ANDROID_LOG_INFO, kTag, "getSettingInt layout=%s offset=%#lx",
                                layout.build, static_cast<unsigned long>(layout.offset));
            return candidate;
        }
    }
    __android_log_print(ANDROID_LOG_ERROR, kTag,
                        "getSettingInt: no known layout matched, GR hook disabled");
    return nullptr;
}

void hookAlgoInterface(HookFunction hook, void *handle) {
    if (!gAlgoInterfaceHandle) gAlgoInterfaceHandle = handle;
    if (originalGetLmtParams) return;
    const char *lmtSymbol = "_ZN7android14getLmtParamsV1ER12ProcessParamPNS_15AlgoProcessDataEi";
    Dl_info info{};
    void *lmtAddress = dlsym(handle, lmtSymbol);
    if (!lmtAddress || !dladdr(lmtAddress, &info)) return;
    // Same setting accessor that getLmtParamsV1 calls at 0x2035b78, reached by fingerprint.
    void *accessor = resolveSettingInt(info.dli_fbase);
    if (!accessor) {
        // Unknown build: leave the accessor NULL. getLmtParams() then reports no GR frame, so the
        // GR LUT tree is simply not swapped -- the camera keeps its own tone path.
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "getSettingInt unavailable: GR frame detection disabled");
    }
    getSettingInt = reinterpret_cast<decltype(getSettingInt)>(accessor);
    hookExport(hook, handle, lmtSymbol, reinterpret_cast<void *>(getLmtParams),
               reinterpret_cast<void **>(&originalGetLmtParams), kTag);
    filterProcessV1 = reinterpret_cast<decltype(filterProcessV1)>(
        dlsym(handle, "_ZN7android15doFilterProcessEPNS_15AlgoProcessDataEi"));
    filterRegisterV1 = reinterpret_cast<decltype(filterRegisterV1)>(
        dlsym(handle, "_ZN7android16doFilterRegisterEPNS_15AlgoProcessDataEi"));
    filterUnregisterV1 = reinterpret_cast<decltype(filterUnregisterV1)>(
        dlsym(handle, "_ZN7android18doFilterUnregisterEPNS_15AlgoProcessDataEi"));
    hookExport(hook, handle, "_ZN7android17doFilterProcessV2EPNS_15AlgoProcessDataEi",
               reinterpret_cast<void *>(filterProcessV2),
               reinterpret_cast<void **>(&originalFilterProcessV2), kTag);
    hookExport(hook, handle,
               "_ZN7android14getVigParamsV1ER12ProcessParamPNS_15AlgoProcessDataERK15ApsBufferPlanes",
               reinterpret_cast<void *>(getVigParams),
               reinterpret_cast<void **>(&originalGetVigParams), kTag);
}

void hookBasicTone(HookFunction hook, void *handle) {
    if (basicToneHooked) return;
    // The X10 router exports getAlgoAPI only; its X9/X10 backends keep these BasicTone_OGL entries.
    // PKJ110 has no router: its libBasicTonePhoto.so *is* the backend, so the same symbol is
    // exported directly and this branch fires on the plain library name.
    const char *coreSymbol = "_ZN13BasicTone_OGL11processCoreEP5ImageS1_PvS2_";
    if (!dlsym(handle, coreSymbol)) return;
    hookExport(hook, handle, coreSymbol, reinterpret_cast<void *>(processCore),
               reinterpret_cast<void **>(&originalProcessCore), kTag);
    if (!originalProcessCore) {
        // Inline hook reported success but handed back no trampoline: the branch is live and the
        // shim would jump to NULL. Latch anyway - re-hooking an already patched address is what
        // caused the crash this guard exists to prevent.
        basicToneHooked = true;
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "processCore hook unusable (no trampoline); BasicTone GR path disabled");
        return;
    }
    // X9U's backend renamed this to loadLut(CamMode, const char*, bool) to carry the profile path.
    // hookExport only ever calls dlsym() on the exact name, so asking for the classic form on a
    // backend that lacks it fails harmlessly - but it must not be treated as a hook, and the GR
    // LMT path swap does not apply there anyway.
    if (dlsym(handle, "_ZN13BasicTone_OGL7loadLutE7CamMode")) {
        hookExport(hook, handle, "_ZN13BasicTone_OGL7loadLutE7CamMode",
                   reinterpret_cast<void *>(loadLut), reinterpret_cast<void **>(&originalLoadLut), kTag);
    } else {
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "loadLut classic symbol absent (X10 backend); GR LMT path swap disabled");
    }
    basicToneHooked = true;
}

}  // namespace

bool basicToneMasterFrame() {
    return activeMaster;
}

void onLibraryLoadedForGr(HookFunction hook, const char *name, void *handle) {
    if (std::strstr(name, "libAlgoInterface.so")) {
        hookAlgoInterface(hook, handle);
    } else if (std::strstr(name, "libBasicTonePhotoX9.so") || std::strstr(name, "libBasicTonePhoto.so")) {
        hookBasicTone(hook, handle);
    }
}
