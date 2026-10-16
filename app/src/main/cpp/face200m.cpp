// 哈苏超清 200MP without the big-face gate, native half (switch and marker: HighPixel200mFace.java).
//
// The change is one decision-tree term (tools/patch_200m_face.py). service.sh stages two copies under
// files/jiege/face200m: one from the payload's decision file and one from the 25MP unlock's. While the
// marker file "enabled" is there, reads of the stock decision path are served from the copy matching the
// 25MP unlock state, so the two switches combine.
//
// The file is read when the camera algorithms initialise, so a change applies after the camera process
// restarts. Runs inside the path hooks: raw syscalls only.
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kDecision[] = "/odm/etc/camera/config/oplus_camera_preview_decision_config.json";
constexpr char kMarker[] = "/data/user/0/com.oplus.camera/files/jiege/face200m/enabled";
constexpr char kBase[] = "/data/user/0/com.oplus.camera/files/jiege/face200m/oplus_camera_preview_decision_config.json";
constexpr char kUnlock25mp[] =
    "/data/user/0/com.oplus.camera/files/jiege/face200m/unlock25mp/oplus_camera_preview_decision_config.json";
constexpr char kUnlock25mpMarker[] = "/data/user/0/com.oplus.camera/files/jiege/unlock25mp/enabled";

bool readable(const char *path) {
    return syscall(SYS_faccessat, AT_FDCWD, path, R_OK, 0) == 0;
}

}  // namespace

const char *face200mRemapPath(const char *path, char *buf, size_t n) {
    if (std::strcmp(path, kDecision) != 0 || !readable(kMarker)) return nullptr;
    std::snprintf(buf, n, "%s", readable(kUnlock25mpMarker) ? kUnlock25mp : kBase);
    return readable(buf) ? buf : nullptr;
}
