#include "capture_dump.h"
#include "ccm_dump_source.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <vector>

namespace {
constexpr char TAG[] = "MuseCcmDump";
using Arg = CaptureKernelArg;
using Args = CaptureKernelArgs;

template <typename T> T field(const void *base, size_t offset) {
    T result;
    memcpy(&result, static_cast<const char *>(base) + offset, sizeof(result));
    return result;
}
template <typename T> T scalar(const Arg &arg) { return field<T>(arg.value, 0); }

// Offsets are from this PMA110 library's exported CLApi dispatch wrappers.
struct Api {
    int (*queueInfo)(void *, unsigned, size_t, void *, size_t *);
    void *(*createProgram)(void *, unsigned, const char **, const size_t *, int *);
    int (*buildProgram)(void *, unsigned, const void **, const char *, void *, void *);
    int (*buildInfo)(void *, void *, unsigned, size_t, void *, size_t *);
    void *(*createKernel)(void *, const char *, int *);
    void *(*createBuffer)(void *, uint64_t, size_t, void *, int *);
    int (*setArg)(void *, unsigned, size_t, const void *);
    int (*enqueue)(void *, void *, unsigned, const size_t *, const size_t *, const size_t *, unsigned, const void *, void *);
    int (*read)(void *, void *, unsigned, size_t, size_t, void *, unsigned, const void *, void *);
    int (*finish)(void *);
    int (*releaseMem)(void *);
    int (*releaseKernel)(void *);
    int (*releaseProgram)(void *);

    explicit Api(void *engine) {
        const void *table = field<void *>(field<void *>(engine, 0x38), 8);
        queueInfo = field<decltype(queueInfo)>(table, 0x58);
        createProgram = field<decltype(createProgram)>(table, 0xa8);
        buildProgram = field<decltype(buildProgram)>(table, 0xc8);
        buildInfo = field<decltype(buildInfo)>(table, 0xd8);
        createKernel = field<decltype(createKernel)>(table, 0xe0);
        createBuffer = field<decltype(createBuffer)>(table, 0x60);
        setArg = field<decltype(setArg)>(table, 0x100);
        enqueue = field<decltype(enqueue)>(table, 0x1a8);
        read = field<decltype(read)>(table, 0x150);
        finish = field<decltype(finish)>(table, 0x148);
        releaseMem = field<decltype(releaseMem)>(table, 0x70);
        releaseKernel = field<decltype(releaseKernel)>(table, 0xf8);
        releaseProgram = field<decltype(releaseProgram)>(table, 0xc0);
    }
};

bool clOk(int code, const char *operation) {
    if (code == 0) return true;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: OpenCL %d", operation, code);
    return false;
}

struct Program {
    Api api;
    void *program = nullptr;
    std::array<void *, 2> kernels{};
    explicit Program(Api a) : api(a) {}
    ~Program() {
        for (void *kernel : kernels) if (kernel) api.releaseKernel(kernel);
        if (program) api.releaseProgram(program);
    }
    bool build(void *context, void *device) {
        int error = 0;
        const char *source = kCcmDumpSource;
        program = api.createProgram(context, 1, &source, nullptr, &error);
        if (!clOk(error, "createProgram")) return false;
        const void *devices[] = {device};
        error = api.buildProgram(program, 1, devices,
            "-cl-std=CL2.0 -O3 -cl-mad-enable -cl-single-precision-constant -DCL_KHR_FP16_ENABLE=1 -DCL_PLATFORM_QCOM", nullptr, nullptr);
        if (!clOk(error, "buildProgram")) {
            size_t size = 0;
            api.buildInfo(program, device, 0x1183, 0, nullptr, &size);
            std::vector<char> log(size + 1);
            api.buildInfo(program, device, 0x1183, size, log.data(), nullptr);
            __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", log.data());
            return false;
        }
        kernels[0] = api.createKernel(program, "capture_linear_rgb", &error);
        if (!clOk(error, "create 2F kernel")) return false;
        kernels[1] = api.createKernel(program, "capture_linear3f_rgb", &error);
        return clOk(error, "create 3F kernel");
    }
};

struct Frame {
    Api api;
    void *buffer = nullptr;
    size_t bufferBytes = 0;
    int width, height;
    std::vector<bool> rows;
    int writtenRows = 0;
    std::string directory, metadata;
    FILE *file = nullptr;
    Frame(Api a, int w, int h) : api(a), width(w), height(h), rows(h, false) {}
    ~Frame() {
        if (file) fclose(file);
        if (buffer) api.releaseMem(buffer);
    }
};

std::mutex captureMutex;
std::unordered_map<void *, std::unique_ptr<Program>> programs;
std::unordered_map<void *, std::unique_ptr<Frame>> frames;

std::string metadata(const Arg *args, int width, int height, const std::array<float, 9> &ccm, const std::string &kernel) {
    std::ostringstream text;
    text << "{\n\"stage\":\"post-WB/CCM, pre-gamma\",\n\"kernel\":\"" << kernel << "\",\n\"file\":\"ccm_rgb.f32\","
         << "\n\"dtype\":\"little-endian float32\",\n\"layout\":\"H,W,RGB interleaved\","
         << "\n\"width\":" << width << ",\n\"height\":" << height
         << ",\n\"row_stride_bytes\":" << size_t(width) * 12
         << ",\n\"bytes\":" << size_t(width) * height * 12
         << ",\n\"source_width\":" << scalar<int>(args[1])
         << ",\n\"source_height\":" << scalar<int>(args[2])
         << ",\n\"source_stride\":" << scalar<int>(args[3]);
    const std::pair<const char *, int> values[] = {{"red_gain",12},{"blue_gain",13},{"awb_max_value",14},
        {"isp_gain",15},{"max_value",20},{"noise_weight",10},{"noise_threshold",11},{"random_num",21},
        {"out_scale_w",24},{"out_scale_h",25},{"white_level",31}};
    for (auto value : values) text << ",\n\"" << value.first << "\":" << scalar<float>(args[value.second]);
    text << ",\n\"lux_index\":" << scalar<int>(args[16])
         << ",\n\"noise_enabled\":" << int(scalar<uint8_t>(args[6]))
         << ",\n\"offset_x\":" << scalar<int>(args[26]) << ",\n\"offset_y\":" << scalar<int>(args[27])
         << ",\n\"ccm\":[";
    for (size_t i = 0; i < ccm.size(); ++i) text << (i ? "," : "") << ccm[i];
    text << "],\n\"method\":\"same-frame stock prefix in separate GPU buffer\"\n}\n";
    return text.str();
}

void capture(void *engine, const Args &builder, unsigned dim, const size_t *offset, const size_t *global, const size_t *local, const std::string &name) {
    const auto count = builder.end - builder.begin;
    if (count != 43 || dim != 2) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ProProc arguments=%td dimension=%u", count, dim);
        return;
    }
    const Arg *args = builder.begin;
    int width = int(global[0]), height = scalar<int>(args[30]);
    int firstRow = int(offset ? offset[1] : 0) + scalar<int>(args[29]);
    int rows = int(global[1]);
    if (width <= 0 || height <= 0 || firstRow < 0 || firstRow + rows > height || (offset && offset[0])) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "unsupported geometry %dx%d rows %d+%d", width, height, firstRow, rows);
        return;
    }
    std::lock_guard<std::mutex> lock(captureMutex);
    Api api(engine);
    void *queue = field<void *>(engine, 0x50), *context = nullptr, *device = nullptr;
    if (!clOk(api.queueInfo(queue, 0x1090, sizeof(context), &context, nullptr), "queue context") ||
        !clOk(api.queueInfo(queue, 0x1091, sizeof(device), &device, nullptr), "queue device")) return;
    auto &program = programs[context];
    if (!program) {
        auto candidate = std::make_unique<Program>(api);
        if (!candidate->build(context, device)) return;
        program = std::move(candidate);
    }
    void *key = scalar<void *>(args[28]);
    auto &slot = frames[key];
    if (!slot || slot->width != width || slot->height != height) {
        auto frame = std::make_unique<Frame>(api, width, height);
        frame->directory = captureDumpDirectory("ccm");
        if (frame->directory.empty()) return;
        frame->file = fopen((frame->directory + "/ccm_rgb.f32").c_str(), "wb+");
        if (!frame->file) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "open RGB output: %s", strerror(errno));
            return;
        }
        std::array<float, 9> ccm{};
        if (!clOk(api.read(queue, scalar<void *>(args[17]), 1, 0, sizeof(ccm), ccm.data(), 0, nullptr, nullptr), "read CCM")) return;
        frame->metadata = metadata(args, width, height, ccm, name);
        slot = std::move(frame);
    }
    Frame &frame = *slot;
    // Store one submission's rows on the GPU; the file receives the full-resolution image.
    size_t tileBytes = size_t(rows) * width * 12;
    if (frame.bufferBytes < tileBytes) {
        if (frame.buffer) { api.releaseMem(frame.buffer); frame.buffer = nullptr; }
        int error = 0;
        frame.buffer = api.createBuffer(context, 1, tileBytes, nullptr, &error);
        if (!clOk(error, "create RGB tile buffer")) { frames.erase(key); return; }
        frame.bufferBytes = tileBytes;
    }
    void *kernel = program->kernels[name.rfind("proproc3F_", 0) == 0 ? 1 : 0];
    bool ok = true;
    for (unsigned i = 0; i < 43 && ok; ++i)
        ok = clOk(api.setArg(kernel, i, i == 28 ? sizeof(frame.buffer) : args[i].size,
                             i == 28 ? &frame.buffer : args[i].value), "set auxiliary arg");
    if (ok) ok = clOk(api.enqueue(queue, kernel, dim, offset, global, local, 0, nullptr, nullptr), "enqueue auxiliary kernel");
    if (ok) ok = clOk(api.finish(queue), "finish auxiliary kernel");
    const size_t start = size_t(firstRow) * width * 12, size = size_t(rows) * width * 12;
    if (ok) ok = fseeko(frame.file, off_t(start), SEEK_SET) == 0;
    std::vector<uint8_t> data(4 * 1024 * 1024);
    for (size_t done = 0; ok && done < size;) {
        size_t bytes = std::min(data.size(), size - done);
        ok = clOk(api.read(queue, frame.buffer, 1, done, bytes, data.data(), 0, nullptr, nullptr), "read RGB") &&
             fwrite(data.data(), 1, bytes, frame.file) == bytes;
        done += bytes;
    }
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "capture failed: %s", frame.directory.c_str());
        frames.erase(key);
        return;
    }
    for (int row = firstRow; row < firstRow + rows; ++row) {
        if (!frame.rows[row]) { frame.rows[row] = true; ++frame.writtenRows; }
    }
    if (frame.writtenRows == height) {
        bool closed = fclose(frame.file) == 0;
        frame.file = nullptr;
        bool saved = closed && captureDumpWrite(frame.directory + "/info.json", frame.metadata.data(), frame.metadata.size());
        __android_log_print(saved ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, TAG,
                            "%s %dx%d %s", saved ? "wrote" : "failed", width, height, frame.directory.c_str());
        frames.erase(key);
    }
}

using RunKernel = int (*)(void *, const char *, const std::string &, const Args &, unsigned, const size_t *, const size_t *, const size_t *, bool);
RunKernel original;
int runKernel(void *engine, const char *source, const std::string &name, const Args &args, unsigned dim,
              const size_t *offset, const size_t *global, const size_t *local, bool sync) {
    if (captureDumpRaw() && name == "pickframe_rawtorgb_com")
        captureRawKernel(engine, args, dim, offset, global);
    if (captureDumpCcm() && (name.rfind("proproc_rgb_p010_u16_c1_buf", 0) == 0 || name.rfind("proproc3F_rgb_p010_u16_c1_buf", 0) == 0))
        capture(engine, args, dim, offset, global, local, name);
    return original(engine, source, name, args, dim, offset, global, local, sync);
}
}

void installCcmDump(HookFunction hook, void *handle) {
    hookExport(hook, handle,
        "_ZN9framework3ocl8CLEngine9runKernelEPKcRKNSt6__ndk112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEERNS0_19CLKernelArgsBuilderEjPKmSG_SG_b",
        reinterpret_cast<void *>(&runKernel), reinterpret_cast<void **>(&original), TAG);
}
