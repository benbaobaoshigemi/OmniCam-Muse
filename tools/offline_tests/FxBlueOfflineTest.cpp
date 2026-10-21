// Offline test of 彩色 Fx 蓝 (app/src/main/cpp/master_fx_blue.h), host C++17; run_native.ps1 builds and runs it.
//   FxBlueOfflineTest                         checks
//   FxBlueOfflineTest <amount> <in> <out>     applies the stage to a raw float32 HLG P3 RGB file
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../../app/src/main/cpp/master_fx_blue.h"

namespace {

int failures = 0;

void check(bool ok, const char *what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

// sRGB-coded colour -> HLG-coded P3.
void fromSrgb(float r, float g, float b, float out[3]) {
    float s[3] = {r, g, b}, lin[3];
    for (int c = 0; c < 3; ++c) lin[c] = s[c] <= 0.04045f ? s[c] / 12.92f : std::pow((s[c] + 0.055f) / 1.055f, 2.4f);
    const float m[9] = {0.8224621f, 0.1775380f, 0.0f, 0.0331941f, 0.9668058f, 0.0f, 0.0170827f, 0.0723974f, 0.9105199f};
    for (int c = 0; c < 3; ++c) out[c] = fxblue::linearToHlg(m[3 * c] * lin[0] + m[3 * c + 1] * lin[1] + m[3 * c + 2] * lin[2]);
}

float stopsAt(float amount, const float hlg[3]) {
    float out[3] = {hlg[0], hlg[1], hlg[2]};
    fxblue::apply(amount, out);
    float before = 0, after = 0;
    for (int c = 0; c < 3; ++c) before += fxblue::hlgToLinear(hlg[c]), after += fxblue::hlgToLinear(out[c]);
    return std::log2(after / before);
}

int runChecks() {
    float worst = 0;
    for (float e = 0; e <= 1.0f; e += 1.0f / 64) {
        float rgb[3] = {e, e, e};
        fxblue::apply(100, rgb);
        for (float v : rgb) worst = std::max(worst, std::abs(v - e));
    }
    check(worst == 0.0f, "neutral axis unchanged at 100");

    worst = 0;
    bool inRange = true;
    const int n = 33;
    for (int b = 0; b < n; ++b)
        for (int g = 0; g < n; ++g)
            for (int r = 0; r < n; ++r) {
                float in[3] = {r / float(n - 1), g / float(n - 1), b / float(n - 1)}, out[3] = {in[0], in[1], in[2]};
                fxblue::apply(0, out);
                for (int c = 0; c < 3; ++c) worst = std::max(worst, std::abs(out[c] - in[c]));
                std::copy(in, in + 3, out);
                fxblue::apply(100, out);
                for (float v : out) inRange = inRange && v >= 0.0f && v <= 1.0f;
            }
    check(worst < 1e-5f, "identity at 0 on a 33 cube");
    check(inRange, "33 cube stays in 0..1 at 100");

    struct Sample { const char *name; float r, g, b; bool moves; };
    const Sample samples[] = {
        {"sky", 0.45f, 0.65f, 0.90f, true},     {"deep sky", 0.20f, 0.45f, 0.85f, true},
        {"primary blue", 0.0f, 0.0f, 1.0f, true}, {"skin", 0.85f, 0.65f, 0.55f, false},
        {"foliage", 0.30f, 0.60f, 0.30f, false}, {"teal", 0.10f, 0.60f, 0.60f, false},
        {"purple", 0.50f, 0.20f, 0.80f, false},  {"red", 0.85f, 0.15f, 0.10f, false},
        {"pale blue-grey", 0.70f, 0.72f, 0.76f, false},
    };
    for (const Sample &sample : samples) {
        float hlg[3];
        fromSrgb(sample.r, sample.g, sample.b, hlg);
        float stops = stopsAt(100, hlg);
        char line[96];
        std::snprintf(line, sizeof line, "%-15s %+.3f EV at 100 (%s)", sample.name, stops, sample.moves ? "darker" : "about still");
        check(sample.moves ? stops < -0.2f : stops > -0.08f, line);
    }

    // Exposure ramps of saturated blues: the output must stay monotonic (gradation kept).
    bool monotonic = true;
    for (float sat : {0.15f, 0.4f, 0.8f, 1.0f}) {
        float previous = -1;
        for (int i = 1; i <= 400; ++i) {
            float k = i / 400.0f, hlg[3];
            fromSrgb(k * (1 - sat), k * (1 - 0.6f * sat), k, hlg);
            fxblue::apply(100, hlg);
            float y = 0.2290f * fxblue::hlgToLinear(hlg[0]) + 0.6917f * fxblue::hlgToLinear(hlg[1]) + 0.0793f * fxblue::hlgToLinear(hlg[2]);
            monotonic = monotonic && y > previous;
            previous = y;
        }
    }
    check(monotonic, "blue exposure ramps stay monotonic at 100");

    float sky[3];
    fromSrgb(0.20f, 0.45f, 0.85f, sky);
    float weak = stopsAt(50, sky), strong = stopsAt(100, sky);
    check(weak < 0 && strong < weak, "amount scales the effect (deep sky 50 vs 100)");

    // Deeper, not greyer: Oklab chroma of in-gamut skies does not drop.
    bool richer = true;
    const float skies[][3] = {{0.45f, 0.65f, 0.90f}, {0.20f, 0.45f, 0.85f}, {0.55f, 0.70f, 0.85f}};
    for (const auto &c : skies) {
        float hlg[3], lin[3], before[3], after[3];
        fromSrgb(c[0], c[1], c[2], hlg);
        for (int k = 0; k < 3; ++k) lin[k] = fxblue::hlgToLinear(hlg[k]);
        fxblue::toOklab(lin, before);
        fxblue::apply(100, hlg);
        for (int k = 0; k < 3; ++k) lin[k] = fxblue::hlgToLinear(hlg[k]);
        fxblue::toOklab(lin, after);
        richer = richer && std::hypot(after[1], after[2]) >= std::hypot(before[1], before[2]) && after[0] < before[0];
    }
    check(richer, "skies get darker with chroma kept or raised at 100");

    std::printf(failures ? "%d failed\n" : "all passed\n", failures);
    return failures ? 1 : 0;
}

int runFile(float amount, const char *in, const char *out) {
    FILE *file = std::fopen(in, "rb");
    if (!file) return 2;
    std::vector<float> data;
    float rgb[3];
    while (std::fread(rgb, sizeof(float), 3, file) == 3) data.insert(data.end(), rgb, rgb + 3);
    std::fclose(file);
    for (size_t i = 0; i < data.size(); i += 3) fxblue::apply(amount, &data[i]);
    file = std::fopen(out, "wb");
    if (!file) return 2;
    std::fwrite(data.data(), sizeof(float), data.size(), file);
    std::fclose(file);
    return 0;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc == 4) return runFile(static_cast<float>(std::atof(argv[1])), argv[2], argv[3]);
    return runChecks();
}
