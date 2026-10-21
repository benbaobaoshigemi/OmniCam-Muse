// Master LUT slots: two tables of the module's own, composed into the stock master BasicTone chain.
//
// The stock chain (libBasicTonePhoto, libCombineLut) merges, per frame, the style LMT, the SC table
// (影调 Cont / 饱和度 Sat) and the CWCM table (冷暖 CoWa / 青品 CyMa) into one table:
// CWCM(SC(LMT(x))). Capture then applies ODT (when on) and the vignette; preview applies nothing more.
// The slots make it Post(CWCM(SC(Pre(LMT(x))))):
//   * Pre, before every stock adjustment: shaping (对比度, later 高光 / 暗部亮度).
//   * Post, after them: colouring on the final colours (彩色 Fx 蓝, later 分离色调, 片基).
// A middle slot (between SC and CWCM) is not built: it would be the CWCM fuse's output, as Pre is
// for SC.
//
// Each slot is an ordered list of stages, each a function of HLG-coded P3 RGB in 0..1 that is the
// identity at its zero parameter. Stages run on the stock table's own entries, so there is no
// resampling beyond the stock merges.
//
// Capture (libBasicTonePhotoX9.so, BasicTone_OGL::loadContext): the merges go through
// BasicTone::MergeLutTet_resize(src, lut, dst, nSrc, nLut), planar tables, channel 0 fastest.
//   resize(LMT, SC∘CWCM, dst)   stock adjustments set        -> src Pre(LMT), dst Post(dst)
//   resize(LMT, ODT, dst)       stock adjustments all zero  -> src Post(Pre(LMT))
//   resize(dst, ODT, dst)       the ODT pass                -> untouched
//   load3DLut(LMT)              all zero, ODT off           -> src Post(Pre(LMT))
// The LMT buffer is cached across frames, so the slots work on copies.
// Preview (libCombineLut.so, Param_BasicTone_CPU::BasicToneProcessNext): MergeLutTet_resize2rgb(LMT,
// SC∘CWCM, dst, nSrc, nLut, begin, end), four threads over [begin, end), dst interleaved RGB. With
// every stock adjustment at zero the stock copies the LMT without a call; the Java half then applies
// Post(Pre()) to the returned table (MasterLutSlots.java).
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "master_fx_blue.h"
#include "native_hooks.h"

namespace {

constexpr char kTag[] = "MuseLutSlots";
constexpr char kRicohLmt[] = "/data/user/0/com.oplus.camera/files/ricoh_gr/lmt";

// BasicTone (libBasicTonePhotoX9.so, X9U stock libBasicTonePhoto md5 827ff5d7…) table pointers.
constexpr size_t kLmtTable = 0xd48;
constexpr size_t kOdtTable = 0xd50;
constexpr size_t kScCwcmTable = 0xd58;

// Slot parameters, in MasterLutSlots.Param order.
enum Param { kContrast, kFxBlue, kParamCount };

/** A cube of n³ RGB entries, planar, channel 0 fastest, as the stock tables. */
struct Cube {
    int n = 0;
    std::vector<float> v;

    // Tetrahedral interpolation, as BasicTone::MergeLutTet.
    void lookup(const float in[3], float out[3]) const {
        const int last = n - 1, plane = n * n * n;
        float f[3];
        int i0[3], i1[3];
        for (int c = 0; c < 3; ++c) {
            float x = std::clamp(in[c], 0.0f, 1.0f) * last;
            i0[c] = std::min(static_cast<int>(x), last);
            i1[c] = std::min(i0[c] + 1, last);
            f[c] = x - i0[c];
        }
        auto at = [&](int r, int g, int b, int c) { return v[c * plane + b * n * n + g * n + r]; };
        // Order the fractions; walk the cube's diagonal through the matching tetrahedron.
        int order[3] = {0, 1, 2};
        std::sort(order, order + 3, [&](int a, int b) { return f[a] > f[b]; });
        int idx[3] = {i0[0], i0[1], i0[2]};
        float weight = 1.0f - f[order[0]];
        for (int c = 0; c < 3; ++c) out[c] = weight * at(idx[0], idx[1], idx[2], c);
        for (int k = 0; k < 3; ++k) {
            idx[order[k]] = i1[order[k]];
            float w = f[order[k]] - (k < 2 ? f[order[k + 1]] : 0.0f);
            for (int c = 0; c < 3; ++c) out[c] += w * at(idx[0], idx[1], idx[2], c);
        }
    }
};

using Stage = std::function<void(float rgb[3])>;

struct Slots {
    std::vector<Stage> pre, post;
};

std::mutex slotsMutex;
std::shared_ptr<const Slots> current = std::make_shared<Slots>();

std::shared_ptr<const Slots> slots() {
    std::lock_guard<std::mutex> lock(slotsMutex);
    return current;
}

bool readCube(const std::string &path, int n, Cube &cube) {
    cube.n = n;
    cube.v.assign(3 * n * n * n, 0.0f);
    FILE *file = std::fopen(path.c_str(), "re");
    if (!file) return false;
    size_t read = std::fread(cube.v.data(), sizeof(float), cube.v.size(), file);
    std::fclose(file);
    return read == cube.v.size();
}

// 对比度: the Ricoh GR contrast, the Cont axis of the GR SC tables at Sat 0 (Lut3 / Lut4 / Lut5 for
// -100 / 0 / 100), blended between nodes as BasicTone::fuseUtilLut does.
bool contrastStage(int value, Stage &stage) {
    static std::mutex cacheMutex;
    static Cube low, zero, high;
    static bool loaded = false;
    {
        std::lock_guard<std::mutex> lock(cacheMutex);
        if (!loaded) {
            std::string root = kRicohLmt;
            loaded = readCube(root + "/SC16Lut3", 16, low) && readCube(root + "/SC16Lut4", 16, zero)
                     && readCube(root + "/SC16Lut5", 16, high);
            if (!loaded) {
                __android_log_print(ANDROID_LOG_ERROR, kTag, "GR SC tables missing under %s", kRicohLmt);
                return false;
            }
        }
    }
    float t = std::min(std::abs(value), 100) / 100.0f;
    auto blended = std::make_shared<Cube>(zero);
    const Cube &end = value < 0 ? low : high;
    for (size_t i = 0; i < blended->v.size(); ++i) blended->v[i] += t * (end.v[i] - zero.v[i]);
    stage = [blended](float rgb[3]) {
        float out[3];
        blended->lookup(rgb, out);
        std::copy(out, out + 3, rgb);
    };
    return true;
}

std::shared_ptr<const Slots> build(const int values[kParamCount]) {
    auto built = std::make_shared<Slots>();
    Stage stage;
    if (values[kContrast] != 0 && contrastStage(values[kContrast], stage)) built->pre.push_back(stage);
    // 彩色 Fx 蓝: 0..100 (Fujifilm 弱 / 强 at about 50 / 100).
    if (values[kFxBlue] > 0) {
        float amount = static_cast<float>(std::min(values[kFxBlue], 100));
        built->post.push_back([amount](float rgb[3]) { fxblue::apply(amount, rgb); });
    }
    return built;
}

void run(const std::vector<Stage> &stages, float rgb[3]) {
    for (const Stage &stage : stages) stage(rgb);
}

// Applies the stages to entries [begin, end) of a planar cube of `count` entries, src to dst.
void applyPlanar(const std::vector<const std::vector<Stage> *> &chain, const float *src, float *dst,
                 int count, int begin, int end) {
    for (int i = begin; i < end; ++i) {
        float rgb[3] = {src[i], src[i + count], src[i + 2 * count]};
        for (const auto *stages : chain) run(*stages, rgb);
        dst[i] = rgb[0];
        dst[i + count] = rgb[1];
        dst[i + 2 * count] = rgb[2];
    }
}

void applyInterleaved(const std::vector<Stage> &stages, float *rgb, int begin, int end) {
    for (int i = begin; i < end; ++i) run(stages, rgb + 3 * i);
}

// ---- capture ----

using Resize = uintptr_t (*)(void *, float *, float *, float *, int, int);
using Load3D = uintptr_t (*)(void *, float *, void *, int);
Resize originalResize;
Load3D originalLoad3D;

float *field(void *object, size_t offset) {
    return *reinterpret_cast<float **>(static_cast<char *>(object) + offset);
}

uintptr_t resize(void *object, float *src, float *lut, float *dst, int nSrc, int nLut) {
    auto active = slots();
    if (!basicToneMasterFrame() || (active->pre.empty() && active->post.empty()) || src != field(object, kLmtTable)) {
        return originalResize(object, src, lut, dst, nSrc, nLut);
    }
    int count = nSrc * nSrc * nSrc;
    std::vector<float> input(3 * static_cast<size_t>(count));
    if (lut == field(object, kScCwcmTable)) {
        applyPlanar({&active->pre}, src, input.data(), count, 0, count);
        uintptr_t result = originalResize(object, input.data(), lut, dst, nSrc, nLut);
        applyPlanar({&active->post}, dst, dst, count, 0, count);
        return result;
    }
    if (lut == field(object, kOdtTable)) {
        applyPlanar({&active->pre, &active->post}, src, input.data(), count, 0, count);
        return originalResize(object, input.data(), lut, dst, nSrc, nLut);
    }
    return originalResize(object, src, lut, dst, nSrc, nLut);
}

uintptr_t load3D(void *object, float *src, void *dst, int n) {
    auto active = slots();
    if (!basicToneMasterFrame() || (active->pre.empty() && active->post.empty()) || src != field(object, kLmtTable)) {
        return originalLoad3D(object, src, dst, n);
    }
    int count = n * n * n;
    std::vector<float> input(3 * static_cast<size_t>(count));
    applyPlanar({&active->pre, &active->post}, src, input.data(), count, 0, count);
    return originalLoad3D(object, input.data(), dst, n);
}

// ---- preview ----

using Resize2Rgb = uintptr_t (*)(float *, float *, float *, int, int, int, int);
Resize2Rgb originalResize2Rgb;
std::atomic<bool> previewMaster{false};
std::atomic<bool> previewApplied{false};

uintptr_t resize2Rgb(float *src, float *lut, float *dst, int nSrc, int nLut, int begin, int end) {
    auto active = slots();
    if (!previewMaster.load() || (active->pre.empty() && active->post.empty())) {
        return originalResize2Rgb(src, lut, dst, nSrc, nLut, begin, end);
    }
    previewApplied.store(true);
    int count = nSrc * nSrc * nSrc;
    // Each call reads only its own [begin, end) of the three planes.
    std::unique_ptr<float[]> input(new float[3 * static_cast<size_t>(count)]);
    applyPlanar({&active->pre}, src, input.get(), count, begin, end);
    uintptr_t result = originalResize2Rgb(input.get(), lut, dst, nSrc, nLut, begin, end);
    applyInterleaved(active->post, dst, begin, end);
    return result;
}

}  // namespace

void onLibraryLoadedForLutSlots(HookFunction hook, const char *name, void *handle) {
    if (std::strstr(name, "libBasicTonePhotoX9.so") && !originalResize) {
        hookExport(hook, handle, "_ZN9BasicTone18MergeLutTet_resizeEPfS0_S0_ii",
                   reinterpret_cast<void *>(resize), reinterpret_cast<void **>(&originalResize), kTag);
        hookExport(hook, handle, "_ZN13BasicTone_OGL9load3DLutIDhEEiPfPT_i",
                   reinterpret_cast<void *>(load3D), reinterpret_cast<void **>(&originalLoad3D), kTag);
    } else if (std::strstr(name, "libCombineLut.so") && !originalResize2Rgb) {
        hookExport(hook, handle, "_Z22MergeLutTet_resize2rgbPfS_S_iiii",
                   reinterpret_cast<void *>(resize2Rgb), reinterpret_cast<void **>(&originalResize2Rgb), kTag);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_local_jiege_hook_masterlut_MasterLutSlots_nativeSetParams(JNIEnv *env, jclass, jintArray values) {
    int params[kParamCount] = {};
    jsize length = std::min<jsize>(env->GetArrayLength(values), kParamCount);
    env->GetIntArrayRegion(values, 0, length, params);
    auto built = build(params);
    std::lock_guard<std::mutex> lock(slotsMutex);
    current = built;
}

extern "C" JNIEXPORT void JNICALL
Java_local_jiege_hook_masterlut_MasterLutSlots_nativePreviewBegin(JNIEnv *, jclass, jboolean master) {
    previewApplied.store(false);
    previewMaster.store(master);
}

// The returned preview table (interleaved RGB, size³ entries). When the stock skipped the merge (every
// stock adjustment at zero) it is the LMT itself, and the slots apply as Post(Pre()).
extern "C" JNIEXPORT void JNICALL
Java_local_jiege_hook_masterlut_MasterLutSlots_nativePreviewEnd(JNIEnv *env, jclass, jfloatArray table, jint size) {
    bool master = previewMaster.exchange(false);
    auto active = slots();
    if (!master || previewApplied.load() || !table || (active->pre.empty() && active->post.empty())) return;
    jsize count = size * size * size;
    if (env->GetArrayLength(table) < 3 * count) return;
    jfloat *rgb = env->GetFloatArrayElements(table, nullptr);
    if (!rgb) return;
    applyInterleaved(active->pre, rgb, 0, count);
    applyInterleaved(active->post, rgb, 0, count);
    env->ReleaseFloatArrayElements(table, rgb, 0);
}
