// 彩色 Fx 蓝: Fujifilm Color Chrome FX Blue as a Post stage of the master LUT slots
// (master_lut_slots.cpp). Saturated blues gain density and depth: in Oklab their lightness drops and
// their chroma rises, hue kept, so a sky reads deeper and richer instead of greyer. Neutrals, skin,
// greens, cyans and purples are untouched.
//
// The weight is the product of three smooth terms in Oklab (from linear P3):
//   hue         flat over 228°..270° (azure to primary blue), raised-cosine to zero at 190° on the cyan
//               side (wide: the gamut boundary sweeps Oklab hue fast there) and at 295° on the purple side;
//   saturation  C / L, which a pure exposure change leaves almost unchanged, smoothstep 0.02 → 0.10:
//               gentle enough not to turn chroma noise into lightness mottling; bluish greys in shade
//               barely move;
//   lightness   smoothstep 0.05 → 0.30 on L, so deep shadows keep their separation.
// At amount 100 a fully weighted colour takes L × (1 − kDarken) and C × (1 + kChroma); the chroma gain
// fades out over C / L 0.15 → 0.35, so skies get richer while already vivid blues only gain density. Where that
// leaves the P3 gamut, the chroma is reduced at the new lightness until it fits (bisection).
// Signals are HLG-coded P3 (ARIB STD-B67), as the slot stages.
#pragma once

#include <algorithm>
#include <cmath>

namespace fxblue {

constexpr float kDarken = 0.15f, kChroma = 0.40f;
constexpr float kHueFlatLow = 228.0f, kHueFlatHigh = 270.0f, kHueZeroLow = 190.0f, kHueZeroHigh = 295.0f;
constexpr float kSatLow = 0.02f, kSatHigh = 0.10f;
constexpr float kRichFull = 0.15f, kRichNone = 0.35f;
constexpr float kLightLow = 0.05f, kLightHigh = 0.30f;

inline float smoothstep(float e0, float e1, float x) {
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// ARIB STD-B67, scene light 0..1.
constexpr float kA = 0.17883277f, kB = 0.28466892f, kC = 0.55991073f;

inline float hlgToLinear(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    return e <= 0.5f ? e * e / 3.0f : (std::exp((e - kC) / kA) + kB) / 12.0f;
}

inline float linearToHlg(float l) {
    l = std::clamp(l, 0.0f, 1.0f);
    return l <= 1.0f / 12.0f ? std::sqrt(3.0f * l) : kA * std::log(12.0f * l - kB) + kC;
}

/** Linear P3 -> Oklab (Oklab M1 after P3 D65 -> XYZ). */
inline void toOklab(const float lin[3], float lab[3]) {
    float l = std::cbrt(0.4813272912f * lin[0] + 0.4620679117f * lin[1] + 0.0564956028f * lin[2]);
    float m = std::cbrt(0.2288381014f * lin[0] + 0.6532343999f * lin[1] + 0.1179544132f * lin[2]);
    float s = std::cbrt(0.0839860178f * lin[0] + 0.2242727893f * lin[1] + 0.6922208389f * lin[2]);
    lab[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s;
    lab[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s;
    lab[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s;
}

inline void fromOklab(const float lab[3], float lin[3]) {
    float l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2];
    float m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2];
    float s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2];
    l = l * l * l, m = m * m * m, s = s * s * s;
    lin[0] = 3.1281105296f * l - 2.2570750190f * m + 0.1293047886f * s;
    lin[1] = -1.0911281613f * l + 2.4132667622f * m - 0.3221681710f * s;
    lin[2] = -0.0260136496f * l - 0.5080276491f * m + 1.5333166822f * s;
}

inline bool inGamut(const float lin[3]) {
    constexpr float kTolerance = 1e-5f;
    return lin[0] >= -kTolerance && lin[1] >= -kTolerance && lin[2] >= -kTolerance && lin[0] <= 1 + kTolerance
           && lin[1] <= 1 + kTolerance && lin[2] <= 1 + kTolerance;
}

inline float saturation(const float lab[3]) { return std::hypot(lab[1], lab[2]) / lab[0]; }

/** Weight 0..1 of an Oklab colour. */
inline float weight(const float lab[3]) {
    if (lab[0] <= 1e-4f) return 0.0f;
    float hue = std::atan2(lab[2], lab[1]) * (180.0f / 3.14159265f);
    if (hue < 0.0f) hue += 360.0f;
    float edge = hue < kHueFlatLow ? (kHueFlatLow - hue) / (kHueFlatLow - kHueZeroLow)
                 : hue > kHueFlatHigh ? (hue - kHueFlatHigh) / (kHueZeroHigh - kHueFlatHigh) : 0.0f;
    float hueWeight = edge >= 1.0f ? 0.0f : 0.5f + 0.5f * std::cos(3.14159265f * edge);
    return hueWeight * smoothstep(kSatLow, kSatHigh, saturation(lab)) * smoothstep(kLightLow, kLightHigh, lab[0]);
}

/** Applies the effect at amount 0..100 to one HLG-coded P3 colour in place. */
inline void apply(float amount, float rgb[3]) {
    float lin[3], lab[3];
    for (int c = 0; c < 3; ++c) lin[c] = hlgToLinear(rgb[c]);
    toOklab(lin, lab);
    float w = weight(lab) * (amount / 100.0f);
    if (w <= 0.0f) return;
    float rich = kChroma * w * (1.0f - smoothstep(kRichFull, kRichNone, saturation(lab)));
    float target[3] = {lab[0] * (1.0f - kDarken * w), lab[1] * (1.0f + rich), lab[2] * (1.0f + rich)};
    fromOklab(target, lin);
    if (!inGamut(lin)) {
        // At the new lightness, the largest chroma up to the target's that fits (below the original's
        // only at the gamut corners, where a darker colour cannot hold the same chroma).
        float lo = 0.0f, hi = 1.0f, at[3];
        for (int i = 0; i < 16; ++i) {
            float t = 0.5f * (lo + hi), gain = (1.0f + rich) * t;
            float probe[3] = {target[0], lab[1] * gain, lab[2] * gain};
            fromOklab(probe, at);
            (inGamut(at) ? lo : hi) = t;
        }
        float gain = (1.0f + rich) * lo;
        float fit[3] = {target[0], lab[1] * gain, lab[2] * gain};
        fromOklab(fit, lin);
    }
    for (int c = 0; c < 3; ++c) rgb[c] = linearToHlg(lin[c]);
}

}  // namespace fxblue
