// Normal-mode 25MP unlock, native half (switch and marker: Unlock25mp.java).
//
// The unlock is six ODM config files with relaxed gates (lux / ISO thresholds, thermal tables and the
// decision tree). service.sh stages the relaxed copies under files/jiege/unlock25mp; while the marker
// file "enabled" is there, reads of the stock paths are served from the staged copies.
//
// Only this process is redirected, so nothing on /odm changes and switching off restores stock behaviour.
// The files are read when the camera algorithms initialise, so a change applies after the camera
// process restarts.
//
// Runs inside the path hooks: no libc stat/access/open here (the hooks would call back in), only
// raw syscalls.
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kOdmConfig[] = "/odm/etc/camera/config/";
constexpr char kRoot[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp/";
constexpr char kMarker[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp/enabled";
// Profile file written by module/service.sh ("PMA110" / "PKJ110"). The staged copies come from the
// X9 Ultra stock /odm, so a device that is not PMA110 must never read them: its capture gate asks for
// sensor modes those files do not carry and the shutter dies on a null array.
constexpr char kProfile[] = "/data/user/0/com.oplus.camera/files/jiege/profile";
constexpr const char *kNames[] = {
    "lighthousemain",
    "lighthousewide",
    "lighthousetele",
    "lighthouseultratele",
    "oplus_camera_preview_decision_config.json",
    "oplus_camera_aps_config",
};

bool readable(const char *path) {
    return syscall(SYS_faccessat, AT_FDCWD, path, R_OK, 0) == 0;
}

/** True when the profile file says PMA110 (raw syscalls: the path hooks must not recurse). */
bool profileIsPma110() {
    int fd = (int)syscall(SYS_openat, AT_FDCWD, kProfile, O_RDONLY | O_CLOEXEC, 0);
    if (fd < 0) return false;
    char buf[16] = {0};
    ssize_t n = syscall(SYS_read, fd, buf, sizeof(buf) - 1);
    syscall(SYS_close, fd);
    return n >= 6 && std::strncmp(buf, "PMA110", 6) == 0;
}

}  // namespace

const char *unlock25mpRemapPath(const char *path, char *buf, size_t n) {
    constexpr size_t prefix = sizeof(kOdmConfig) - 1;
    if (std::strncmp(path, kOdmConfig, prefix) != 0) return nullptr;
    const char *name = path + prefix;
    bool known = false;
    for (const char *candidate : kNames) {
        if (std::strcmp(name, candidate) == 0) {
            known = true;
            break;
        }
    }
    if (!known || !readable(kMarker)) return nullptr;
    if (!profileIsPma110()) return nullptr;
    std::snprintf(buf, n, "%s%s", kRoot, name);
    return readable(buf) ? buf : nullptr;
}
