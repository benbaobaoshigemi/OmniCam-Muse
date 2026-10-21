// HDRTransform intermediate dump: for each master capture, the input of HDRTransform (the HLG
// P010 frame after BasicTone, Meishe and the soft light) and its output (the NV21 SDR base that
// becomes the JPEG), stored raw with their geometry. They show what HDRTransform does to the image
// between the preview's rendition and the saved photo.
//
// HDRTransformImpl::processMaster{V2,Quick}(this, InputImage *in, Image *out, void *param, void *bufs):
//   *(in) +0x20 w, +0x24 h, +0x28 row stride in bytes, +0x2c slice height (P010)
//   out   +0x20 w, +0x24 h, +0x28 row stride, +0x2c slice height (NV21)
//   param +0x130 the input buffer's native handle; bufs +0x28 the output buffer's native handle
// The first fd of each handle is the dma-buf of the whole image.
//
// Files: Android/data/com.oplus.camera/files/muse/dumps/hdrtransform/<capture>/.
// The buffers are copied during the call and written on a thread, so the capture is held up by the copy only.
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <linux/dma-buf.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "capture_dump.h"

namespace {

constexpr const char *TAG = "MuseHdrtDump";





struct NativeHandle {
    int version, numFds, numInts;
    int data[0];
};

template <typename T>
T fieldAt(const void *base, size_t offset) {
    T v;
    std::memcpy(&v, static_cast<const uint8_t *>(base) + offset, sizeof v);
    return v;
}

struct Geometry {
    int w = 0, h = 0, stride = 0, slice = 0;
};

Geometry geometryAt(const void *image) {
    Geometry g;
    if (!image) return g;
    g.w = fieldAt<int>(image, 0x20);
    g.h = fieldAt<int>(image, 0x24);
    g.stride = fieldAt<int>(image, 0x28);
    g.slice = fieldAt<int>(image, 0x2c);
    return g;
}

// Copy of a whole dma-buf, or empty with the reason logged.
std::vector<uint8_t> copyBuffer(const NativeHandle *handle, const char *what) {
    std::vector<uint8_t> data;
    if (!handle || handle->version != int(sizeof(NativeHandle)) || handle->numFds < 1 || handle->numFds > 8) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: no native handle", what);
        return data;
    }
    int fd = handle->data[0];
    off_t size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: fd %d has no size (errno %d)", what, fd, errno);
        return data;
    }
    void *base = mmap(nullptr, size_t(size), PROT_READ, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: fd %d not mappable (errno %d)", what, fd, errno);
        return data;
    }
    dma_buf_sync sync{DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ};
    ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    data.assign(static_cast<uint8_t *>(base), static_cast<uint8_t *>(base) + size);
    sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ;
    ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    munmap(base, size_t(size));
    return data;
}

void dump(const char *path, void *in, void *out, void *param, void *bufs) {
    Geometry gi = geometryAt(in ? fieldAt<void *>(in, 0) : nullptr), go = geometryAt(out);
    auto input = std::make_shared<std::vector<uint8_t>>(
        copyBuffer(param ? fieldAt<const NativeHandle *>(param, 0x130) : nullptr, "input"));
    auto output = std::make_shared<std::vector<uint8_t>>(
        copyBuffer(bufs ? fieldAt<const NativeHandle *>(bufs, 0x28) : nullptr, "output"));
    if (input->empty() && output->empty()) return;

    std::string dir = captureDumpDirectory("hdrtransform");
    if (dir.empty()) return;
    char info[512];
    std::snprintf(info, sizeof info,
                  "{\n  \"path\": \"%s\",\n"
                  "  \"input\": {\"file\": \"in.p010\", \"format\": \"P010\", \"width\": %d, \"height\": %d, \"row_stride_bytes\": %d, "
                  "\"slice_height\": %d, \"bytes\": %zu},\n"
                  "  \"output\": {\"file\": \"out.nv21\", \"format\": \"NV21\", \"width\": %d, \"height\": %d, \"row_stride_bytes\": %d, "
                  "\"slice_height\": %d, \"bytes\": %zu}\n}\n",
                  path, gi.w, gi.h, gi.stride, gi.slice, input->size(), go.w, go.h, go.stride, go.slice, output->size());
    std::string text = info;
    std::thread([dir, input, output, text] {
        bool ok = captureDumpWrite(dir + "/info.json", text.data(), text.size()) &&
                  (input->empty() || captureDumpWrite(dir + "/in.p010", input->data(), input->size())) &&
                  (output->empty() || captureDumpWrite(dir + "/out.nv21", output->data(), output->size()));
        __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG, "%s %s (errno %d)", ok ? "wrote" : "failed", dir.c_str(),
                            ok ? 0 : errno);
    }).detach();
}

using ProcessMaster = int (*)(void *, void *, void *, void *, void *);

template <int Slot>
struct MasterHook {
    static ProcessMaster orig;
    static const char *path;
    static int call(void *self, void *in, void *out, void *param, void *bufs) {
        int ret = orig(self, in, out, param, bufs);
        if (ret == 0 && captureDumpHdrt()) dump(path, in, out, param, bufs);
        return ret;
    }
};
template <int Slot> ProcessMaster MasterHook<Slot>::orig = nullptr;
template <int Slot> const char *MasterHook<Slot>::path = nullptr;

template <int Slot>
void hookMaster(HookFunction hook, void *handle, const char *symbol, const char *path) {
    MasterHook<Slot>::path = path;
    hookExport(hook, handle, symbol, reinterpret_cast<void *>(&MasterHook<Slot>::call),
               reinterpret_cast<void **>(&MasterHook<Slot>::orig), TAG);
}

constexpr const char *kV2 = "_ZN16HDRTransformImpl15processMasterV2EP10InputImageP5ImagePvS4_";
constexpr const char *kQuick = "_ZN16HDRTransformImpl18processMasterQuickEP10InputImageP5ImagePvS4_";

}  // namespace

void installHdrtDump(HookFunction hook, const char *name, void *handle) {
    static bool camera = false, quick = false;
    if (!camera && std::strstr(name, "libOPAlgoCamHDRTransformCamera.so")) {
        camera = true;
        hookMaster<0>(hook, handle, kV2, "main");
        hookMaster<1>(hook, handle, kQuick, "quick");
    } else if (!quick && std::strstr(name, "libOPAlgoCamHDRTransformQuick.so")) {
        quick = true;
        hookMaster<2>(hook, handle, kV2, "main-q");
        hookMaster<3>(hook, handle, kQuick, "quick-q");
    }
}
