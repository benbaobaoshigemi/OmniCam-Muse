#include "capture_dump.h"

#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
std::atomic<int> options{0};
std::atomic<unsigned> sequence{0};
constexpr char kRoot[] = "/sdcard/Android/data/com.oplus.camera/files/muse/dumps";
}

bool captureDumpRaw() { return (options.load() & 1) != 0; }
bool captureDumpCcm() { return (options.load() & 2) != 0; }
bool captureDumpHdrt() { return (options.load() & 4) != 0; }

std::string captureDumpDirectory(const char *stage) {
    timespec now{};
    clock_gettime(CLOCK_REALTIME, &now);
    tm local{};
    localtime_r(&now.tv_sec, &local);
    char stamp[40];
    strftime(stamp, sizeof(stamp), "%Y%m%d_%H%M%S", &local);
    char suffix[100];
    snprintf(suffix, sizeof(suffix), "/%s/%s_%03ld_%d_%u", stage, stamp,
             now.tv_nsec / 1000000, getpid(), sequence.fetch_add(1));
    std::string path = std::string(kRoot) + suffix;
    for (size_t i = 1; i <= path.size(); ++i) {
        if (i != path.size() && path[i] != '/') continue;
        std::string part = path.substr(0, i);
        if (mkdir(part.c_str(), 0770) != 0 && errno != EEXIST) {
            __android_log_print(ANDROID_LOG_ERROR, "MuseCaptureDump", "mkdir %s: %s", part.c_str(), strerror(errno));
            return {};
        }
    }
    return path;
}

bool captureDumpWrite(const std::string &path, const void *data, size_t size) {
    FILE *file = fopen(path.c_str(), "wb");
    if (!file) {
        __android_log_print(ANDROID_LOG_ERROR, "MuseCaptureDump", "open %s: %s", path.c_str(), strerror(errno));
        return false;
    }
    bool ok = fwrite(data, 1, size, file) == size;
    if (fclose(file) != 0) ok = false;
    if (!ok) __android_log_print(ANDROID_LOG_ERROR, "MuseCaptureDump", "write %s: %s", path.c_str(), strerror(errno));
    return ok;
}

void onLibraryLoadedForCaptureDump(HookFunction hook, const char *name, void *handle) {
    static bool hybrid = false;
    if (!hybrid && strstr(name, "libOPAlgoCamHybridRaw.so")) {
        hybrid = true;
        installCcmDump(hook, handle);
    }
    installHdrtDump(hook, name, handle);
}

extern "C" JNIEXPORT void JNICALL
Java_local_jiege_hook_capture_CaptureDumps_nativeConfigure(JNIEnv *, jclass, jboolean raw, jboolean ccm, jboolean hdrt) {
    options.store((raw ? 1 : 0) | (ccm ? 2 : 0) | (hdrt ? 4 : 0));
    __android_log_print(ANDROID_LOG_INFO, "MuseCaptureDump", "RAW=%d ccmVec=%d HDRTransform=%d", raw, ccm, hdrt);
}
