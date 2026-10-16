#include <cstdint>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <android/log.h>

#include "native_hooks.h"

// POP port, native half: the POP retro settings and Meishe LUTs are staged by service.sh under
// the camera's private files. Reads of the ODM paths are redirected there when a staged copy
// exists, so no ODM directory has to be replaced for POP. Meishe LUT reads are also served from
// the master filters the user imported (files/jiege/lut, see MasterFilterSync).
// The same hooks serve the natural photo mode's tuning json (natural_mode.cpp).
static int (*originalOpenat)(int, const char *, int, ...);
static int (*originalFaccessat)(int, const char *, int, int);
static int (*originalFstatat)(int, const char *, void *, int);
static FILE *(*originalFopen)(const char *, const char *);
// Bionic's fortified open() does not go through openat(); the tuning json is read this way.
static int (*originalOpen2)(const char *, int);
// Plain open(): the decision-tree json (libAlgoInterface) is read with it.
static int (*originalOpen)(const char *, int, ...);

static constexpr char kOdmRetro[] = "/odm/etc/camera/basictone/setting_Retro";
static constexpr char kAppRetro[] = "/data/user/0/com.oplus.camera/files/pop_port/setting_Retro";
static constexpr char kOdmLut[] = "/odm/etc/camera/meishe_lut/";
static constexpr char kAppLut[] = "/data/user/0/com.oplus.camera/files/pop_port/meishe_lut/";
// Master filters the user imported in the settings app (MasterFilterSync copies them here).
static constexpr char kImportedLut[] = "/data/user/0/com.oplus.camera/files/jiege/lut/";

static thread_local int bypass;

static bool destExists(const char *path) {
    if (!path || bypass) {
        return false;
    }
    bypass = 1;
    int ok = access(path, F_OK);
    bypass = 0;
    return ok == 0;
}

static const char *remap(const char *path, char *buf, size_t n) {
    if (bypass || !path) {
        return path;
    }
    // Master colour live directories (master_color.cpp): bake a preview table on first read, then
    // serve LMTLut1..9 as LMTLut0 (unless the directory has its own) and every other name from the stock
    // ODM lmt directory.
    masterColorPreparePath(path);
    if (const char *mapped = masterColorRemapPath(path, buf, n)) {
        return mapped;
    }
    if (const char *mapped = naturalRemapPath(path, buf, n)) {
        return mapped;
    }
    if (const char *mapped = face200mRemapPath(path, buf, n)) {
        return mapped;
    }
    if (const char *mapped = unlock25mpRemapPath(path, buf, n)) {
        return mapped;
    }
    if (std::strncmp(path, kOdmRetro, sizeof(kOdmRetro) - 1) == 0) {
        std::snprintf(buf, n, "%s%s", kAppRetro, path + sizeof(kOdmRetro) - 1);
        if (destExists(buf)) {
            return buf;
        }
        return path;
    }
    if (std::strncmp(path, kOdmLut, sizeof(kOdmLut) - 1) == 0) {
        std::snprintf(buf, n, "%s%s", kAppLut, path + sizeof(kOdmLut) - 1);
        if (destExists(buf)) {
            return buf;
        }
        std::snprintf(buf, n, "%s%s", kImportedLut, path + sizeof(kOdmLut) - 1);
        if (destExists(buf)) {
            return buf;
        }
    }
    return path;
}

static int hookedOpenat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        __builtin_va_list args;
        __builtin_va_start(args, flags);
        mode = __builtin_va_arg(args, int);
        __builtin_va_end(args);
    }
    char buf[512];
    const char *mapped = remap(path, buf, sizeof(buf));
    if (flags & O_CREAT) {
        return originalOpenat(dirfd, mapped, flags, mode);
    }
    return originalOpenat(dirfd, mapped, flags);
}

static int hookedOpen(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        __builtin_va_list args;
        __builtin_va_start(args, flags);
        mode = __builtin_va_arg(args, int);
        __builtin_va_end(args);
    }
    char buf[512];
    const char *mapped = remap(path, buf, sizeof(buf));
    if (flags & (O_CREAT | O_TMPFILE)) {
        return originalOpen(mapped, flags, mode);
    }
    return originalOpen(mapped, flags);
}

static int hookedFaccessat(int dirfd, const char *path, int mode, int flags) {
    char buf[512];
    return originalFaccessat(dirfd, remap(path, buf, sizeof(buf)), mode, flags);
}

static int hookedFstatat(int dirfd, const char *path, void *st, int flags) {
    char buf[512];
    return originalFstatat(dirfd, remap(path, buf, sizeof(buf)), st, flags);
}

static FILE *hookedFopen(const char *path, const char *mode) {
    char buf[512];
    return originalFopen(remap(path, buf, sizeof(buf)), mode);
}

static int hookedOpen2(const char *path, int flags) {
    char buf[512];
    return originalOpen2(remap(path, buf, sizeof(buf)), flags);
}

static void hookSymbol(HookFunction hook, void *handle, const char *symbol, void *replacement, void **original) {
    hookExport(hook, handle, symbol, replacement, original, "PopPort");
}

void installPopPathRemap(HookFunction hook) {
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc) {
        hookSymbol(hook, libc, "openat", reinterpret_cast<void *>(hookedOpenat), reinterpret_cast<void **>(&originalOpenat));
        // Bionic access() delegates to faccessat(). Hooking both also relocates
        // access()'s PLT tail branch, which is not a valid indirect BTI landing.
        hookSymbol(hook, libc, "faccessat", reinterpret_cast<void *>(hookedFaccessat), reinterpret_cast<void **>(&originalFaccessat));
        hookSymbol(hook, libc, "fstatat", reinterpret_cast<void *>(hookedFstatat), reinterpret_cast<void **>(&originalFstatat));
        hookSymbol(hook, libc, "fopen", reinterpret_cast<void *>(hookedFopen), reinterpret_cast<void **>(&originalFopen));
        hookSymbol(hook, libc, "__open_2", reinterpret_cast<void *>(hookedOpen2), reinterpret_cast<void **>(&originalOpen2));
        hookSymbol(hook, libc, "open", reinterpret_cast<void *>(hookedOpen), reinterpret_cast<void **>(&originalOpen));
    }
    __android_log_print(ANDROID_LOG_INFO, "PopPort", "native path remap ready");
}
