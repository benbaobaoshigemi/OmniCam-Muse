#include "capture_dump.h"
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <vector>

namespace {
template <typename T> T field(const void *base, size_t offset) {
    T value;
    memcpy(&value, static_cast<const char *>(base) + offset, sizeof(value));
    return value;
}
template <typename T> T arg(const CaptureKernelArgs &args, unsigned index) {
    return field<T>(args.begin[index].value, 0);
}
}

// Preserve the sensor buffer read by PickFrame, before unpack/repack, JDD and RGB fusion.
void captureRawKernel(void *engine, const CaptureKernelArgs &args, unsigned dim, const size_t *offset, const size_t *global) {
    if (args.end - args.begin != 20 || dim != 2) {
        __android_log_print(ANDROID_LOG_ERROR, "MuseRawDump", "unexpected PickFrame arguments");
        return;
    }
    const void *table = field<void *>(field<void *>(engine, 0x38), 8);
    auto info = field<int (*)(void *, unsigned, size_t, void *, size_t *)>(table, 0x80);
    auto read = field<int (*)(void *, void *, unsigned, size_t, size_t, void *, unsigned, const void *, void *)>(table, 0x150);
    void *queue = field<void *>(engine, 0x50), *memory = arg<void *>(args, 0);
    size_t bytes = 0;
    int error = info(memory, 0x1102, sizeof(bytes), &bytes, nullptr);
    if (error != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "MuseRawDump", "RAW memory size: OpenCL %d", error);
        return;
    }
    std::string directory = captureDumpDirectory("raw");
    if (directory.empty()) return;
    FILE *file = fopen((directory + "/sensor.raw").c_str(), "wb");
    if (!file) {
        __android_log_print(ANDROID_LOG_ERROR, "MuseRawDump", "open RAW output failed");
        return;
    }
    std::vector<uint8_t> data(4 * 1024 * 1024);
    bool ok = true;
    for (size_t position = 0; ok && position < bytes;) {
        size_t count = std::min(data.size(), bytes - position);
        error = read(queue, memory, 1, position, count, data.data(), 0, nullptr, nullptr);
        ok = error == 0 && fwrite(data.data(), 1, count, file) == count;
        position += count;
    }
    if (fclose(file) != 0) ok = false;
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, "MuseRawDump", "RAW read/write failed: OpenCL %d", error);
        return;
    }
    std::ostringstream text;
    text << "{\n\"stage\":\"HybridRaw sensor input at PickFrame\",\n\"file\":\"sensor.raw\","
         << "\n\"kernel\":\"pickframe_rawtorgb_com\",\n\"buffer_bytes\":" << bytes
         << ",\n\"row_stride_bytes\":" << arg<int>(args, 5)
         << ",\n\"format\":\"" << (arg<int>(args, 6) == 1 ? "packed RAW10" : "little-endian uint16") << "\""
         << ",\n\"bayer_pattern\":" << arg<int>(args, 7)
         << ",\n\"zoom_mode\":" << arg<int>(args, 8)
         << ",\n\"is_200m\":" << arg<int>(args, 9)
         << ",\n\"black_level\":" << arg<uint16_t>(args, 10)
         << ",\n\"adrc_gain\":" << arg<float>(args, 11)
         << ",\n\"shift_num\":" << arg<uint16_t>(args, 12)
         << ",\n\"image_bit_depth\":" << arg<int>(args, 13)
         << ",\n\"read_grid\":{\"width\":" << global[0] * 8 << ",\"height\":" << global[1]
         << ",\n\"offset_x\":" << (offset ? offset[0] * 8 : 0) << ",\"offset_y\":" << (offset ? offset[1] : 0)
         << "}\n}\n";
    std::string metadata = text.str();
    ok = captureDumpWrite(directory + "/info.json", metadata.data(), metadata.size());
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "MuseRawDump", "%s %zu bytes %s",
                        ok ? "wrote" : "failed", bytes, directory.c_str());
}
