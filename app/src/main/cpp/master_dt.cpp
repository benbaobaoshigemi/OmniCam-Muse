// Ports of PhotonCamera-bjzhou raw/AgXToneAlgorithm.kt, DarktableSigmoidToneAlgorithm.kt and
// DarktableFilmicToneAlgorithm.kt (GLSL applyEngineTone), stage by stage; see tools/mastercolor/engines_dt.py.
#include "master_dt.h"

#include <algorithm>
#include <cmath>

namespace mastercolor {
namespace dt {
namespace {

inline double dot3(const double a[3], const double b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

void mul3(const double m[9], const double v[3], double o[3]) {
    double r[3] = {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                   m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    o[0] = r[0], o[1] = r[1], o[2] = r[2];
}

double fract(double x) { return x - std::floor(x); }

void rgbToHsv(const double c[3], double hsv[3]) {
    const double k[4] = {0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0};
    double p[4];
    if (c[1] >= c[2]) p[0] = c[1], p[1] = c[2], p[2] = k[0], p[3] = k[1];
    else p[0] = c[2], p[1] = c[1], p[2] = k[3], p[3] = k[2];
    double q[4];
    if (c[0] >= p[0]) q[0] = c[0], q[1] = p[1], q[2] = p[2], q[3] = p[0];
    else q[0] = p[0], q[1] = p[1], q[2] = p[3], q[3] = c[0];
    double d = q[0] - std::min(q[3], q[1]);
    const double e = 1e-10;
    hsv[0] = std::fabs(q[2] + (q[3] - q[1]) / (6.0 * d + e));
    hsv[1] = d / (q[0] + e);
    hsv[2] = q[0];
}

void hsvToRgb(const double h[3], double c[3]) {
    const double k[3] = {1.0, 2.0 / 3.0, 1.0 / 3.0};
    for (int i = 0; i < 3; i++) {
        double p = std::fabs(fract(h[0] + k[i]) * 6.0 - 3.0);
        c[i] = h[2] * ((1 - h[1]) + h[1] * std::min(std::max(p - 1.0, 0.0), 1.0));
    }
}

// ---------------------------------------------------------------- AgX
constexpr double kAgxEps = 0.000001, kAgxRange = 16.5, kAgxMinRange = 0.2, kAgxGamma = 2.2;
constexpr double kAgxPivotY = 0.4586564469, kAgxSlope = 3.0, kAgxHueMix = 0.6;
const double kAgxBaseToRender[9] = {0.8535098168, 0.0870498824, 0.0594403008, 0.1209748385, 0.7561015246,
                                    0.1229236368, 0.0964595535, 0.0689548151, 0.8345856314};
const double kAgxRenderToBase[9] = {1.1203173359, -0.0999545154, -0.0203628205, -0.1213527019, 1.1417155224,
                                    -0.0203628205, -0.1213527019, -0.0999545154, 1.2213072173};
const double kAgxLuma[3] = {0.2658180370, 0.5984698605, 0.1357121025};

void agxCompress(const double c[3], double o[3]) {
    double yIn = dot3(c, kAgxLuma);
    double mx = std::max(c[0], std::max(c[1], c[2]));
    double opp[3] = {mx - c[0], mx - c[1], mx - c[2]};
    double yComp = std::max(opp[0], std::max(opp[1], opp[2])) - dot3(opp, kAgxLuma) + yIn;
    double offset = std::max(-std::min(c[0], std::min(c[1], c[2])), 0.0);
    double ro[3] = {c[0] + offset, c[1] + offset, c[2] + offset};
    double mo = std::max(ro[0], std::max(ro[1], ro[2]));
    double oo[3] = {mo - ro[0], mo - ro[1], mo - ro[2]};
    double yNew = std::max(oo[0], std::max(oo[1], oo[2])) - dot3(oo, kAgxLuma) + dot3(ro, kAgxLuma);
    double ratio = (yNew > yComp && yNew > kAgxEps) ? yComp / yNew : 1.0;
    for (int i = 0; i < 3; i++) o[i] = ratio * ro[i];
}

struct AgxCurve {
    double black, rangeEv, pivotX, slope, intercept;
    double toePower, toeScale, toeFallbackPower, toeFallbackCoefficient;
    double shoulderPower, shoulderScale, shoulderFallbackPower, shoulderFallbackCoefficient;
    bool convexToe, concaveShoulder;
};

double agxScale(double limitX, double limitY, double tx, double ty, double slope, double power) {
    double projected = slope * std::max(kAgxEps, limitX - tx);
    double actual = std::max(kAgxEps, limitY - ty);
    double base = std::max(kAgxEps, std::pow(actual, -power) - std::pow(projected, -power));
    return std::min(1000000000.0, std::pow(base, -1.0 / power));
}

AgxCurve agxCurveFor(const AgxParams &p) {
    AgxCurve c{};
    double black = std::min(double(p.black), double(p.white) - kAgxMinRange);
    double white = std::max(double(p.white), black + kAgxMinRange);
    c.black = black;
    c.rangeEv = std::max(kAgxMinRange, white - black);
    c.pivotX = std::min(std::max(-black / c.rangeEv, kAgxEps), 1.0 - kAgxEps);
    double py = kAgxPivotY;
    c.slope = kAgxSlope * (c.rangeEv / kAgxRange);
    c.toePower = std::max(0.01, double(p.toe));
    c.toeScale = -agxScale(1.0, 1.0, 1.0 - c.pivotX, 1.0 - py, c.slope, c.toePower);
    double toeLength = std::max(kAgxEps, c.pivotX), toeDy = std::max(kAgxEps, py);
    c.convexToe = toeDy / toeLength > c.slope;
    c.toeFallbackPower = c.slope * toeLength / toeDy;
    c.toeFallbackCoefficient = toeDy / std::pow(toeLength, c.toeFallbackPower);
    c.intercept = py - c.slope * c.pivotX;
    c.shoulderPower = std::max(0.01, double(p.shoulder));
    c.shoulderScale = agxScale(1.0, 1.0, c.pivotX, py, c.slope, c.shoulderPower);
    double shoulderLength = std::max(kAgxEps, 1.0 - c.pivotX), shoulderDy = std::max(kAgxEps, 1.0 - py);
    c.concaveShoulder = shoulderDy / shoulderLength > c.slope;
    c.shoulderFallbackPower = c.slope * shoulderLength / shoulderDy;
    c.shoulderFallbackCoefficient = shoulderDy / std::pow(shoulderLength, c.shoulderFallbackPower);
    return c;
}

double agxScaledSigmoid(double v, double scale, double slope, double power, double tx, double ty) {
    double x = slope * (v - tx) / scale;
    return scale * (x / std::pow(1.0 + std::pow(x, power), 1.0 / power)) + ty;
}

double agxCurve(const AgxCurve &c, double v) {
    double py = kAgxPivotY, r;
    if (v < c.pivotX) {
        r = c.convexToe ? (v < 0.0 ? 0.0 : std::max(0.0, c.toeFallbackCoefficient * std::pow(v, c.toeFallbackPower)))
                        : agxScaledSigmoid(v, c.toeScale, c.slope, c.toePower, c.pivotX, py);
    } else if (v <= c.pivotX) {
        r = c.slope * v + c.intercept;
    } else {
        r = c.concaveShoulder
                ? (v >= 1.0 ? 1.0 : 1.0 - std::max(0.0, c.shoulderFallbackCoefficient * std::pow(1.0 - v, c.shoulderFallbackPower)))
                : agxScaledSigmoid(v, c.shoulderScale, c.slope, c.shoulderPower, c.pivotX, py);
    }
    return std::min(std::max(r, 0.0), 1.0);
}

double agxLog(const AgxCurve &c, double v) {
    double relative = std::max(kAgxEps, v / 0.18);
    return std::min(std::max((std::log2(std::max(relative, 0.0)) - c.black) / c.rangeEv, 0.0), 1.0);
}

// ---------------------------------------------------------------- filmic
constexpr double kNormMin = 0.0000152587890625, kGreySource = 0.1845, kOutputPower = 3.614815775;
constexpr double kDisplayBlack = 0.0001517634, kY1931 = 1.05785528, kYrgR = 0.21902143, kYrgG = 0.54371398;
constexpr double kMaxChroma = 1.0e20;
const double kBt2020ToLms[9] = {0.4067763460, 0.6178051991, 0.0458445893, 0.0677498629, 0.7489671634,
                                0.1001665160, 0.0221408642, -0.0153252587, 0.5876294574};
const double kLmsToBt2020[9] = {2.8380181184, -2.3373915374, 0.1770173235, -0.2415770666, 1.5294941187,
                                -0.2418685622, -0.1132319053, 0.1279577808, 1.6887750778};
const double kSrgbToLms[9] = {0.2986531876, 0.7060763220, 0.0656966248, 0.0959000021, 0.7198304285,
                              0.1011531117, 0.0224644230, 0.0449176289, 0.5270630110};
const double kLmsToSrgb[9] = {4.8627131007, -4.7893329888, 0.3130405567, -0.6262137162, 2.0228185813,
                              -0.3101607577, -0.1538905312, 0.0317407724, 1.9103966448};
const double kLmsToFilmlight[9] = {1.08771930, -0.66666667, 0.02061856, -0.08771930, 1.66666667,
                                   -0.05154639, 0.0, 0.0, 1.03092784};
const double kFilmlightToLms[9] = {0.95, 0.38, 0.0, 0.05, 0.62, 0.03, 0.0, 0.0, 0.97};

double filmicLogEncode(const FilmicUniforms &u, double v) {
    double safe = std::max(v, kNormMin);
    return std::min(std::max((std::log2(safe / kGreySource) - u.black) / std::max(double(u.dynamicRange), 0.2), 0.0), 1.0);
}

double filmicSpline(const FilmicUniforms &u, double x) {
    auto poly = [&](int c) {
        return u.m[0][c] + x * (u.m[1][c] + x * (u.m[2][c] + x * (u.m[3][c] + x * u.m[4][c])));
    };
    if (x < u.latitudeMin) return poly(0);
    if (x > u.latitudeMax) return poly(1);
    return u.m[0][2] + x * u.m[1][2];
}

struct Ych {
    double y, c, cosH, sinH;
};

Ych rgbToYch(const double rgb[3], bool srgb) {
    double lms[3];
    mul3(srgb ? kSrgbToLms : kBt2020ToLms, rgb, lms);
    double y = 0.68990272 * lms[0] + 0.34832189 * lms[1];
    double sum = lms[0] + lms[1] + lms[2];
    double n[3] = {0, 0, 0};
    if (std::fabs(sum) > 1e-8) n[0] = lms[0] / sum, n[1] = lms[1] / sum, n[2] = lms[2] / sum;
    double fl[3];
    mul3(kLmsToFilmlight, n, fl);
    double r = fl[0] - kYrgR, g = fl[1] - kYrgG;
    double c = std::sqrt(r * r + g * g);
    return {y, c, c > 0 ? r / c : 1.0, c > 0 ? g / c : 0.0};
}

void ychToRgb(const Ych &y, bool srgb, double rgb[3]) {
    double yr = y.c * y.cosH + kYrgR, yg = y.c * y.sinH + kYrgG;
    double fl[3] = {yr, yg, 1.0 - yr - yg}, n[3];
    mul3(kFilmlightToLms, fl, n);
    double den = 0.68990272 * n[0] + 0.34832189 * n[1];
    double scale = std::fabs(den) > 1e-8 ? y.y / den : 0.0;
    double lms[3] = {n[0] * scale, n[1] * scale, n[2] * scale};
    mul3(srgb ? kLmsToSrgb : kLmsToBt2020, lms, rgb);
}

double clipWhiteRaw(const double co[3], double tw, double y, double cosH, double sinH) {
    double dy = co[0] * (0.979381443298969 * cosH + 0.391752577319588 * sinH) +
                co[1] * (0.0206185567010309 * cosH + 0.608247422680412 * sinH) - co[2] * (cosH + sinH);
    double dt = tw * (0.68285981628866 * cosH + 0.482137060515464 * sinH);
    if (std::fabs(dy) <= 1e-8) return kMaxChroma;
    if (y <= dt / dy) return kMaxChroma;
    double den = y * dy - dt;
    if (std::fabs(den) <= 1e-8) return kMaxChroma;
    double num = -0.427506877216495 *
                 (y * (co[0] + 0.856492345150334 * co[1] + 0.554995960637719 * co[2]) - 0.988237752433297 * tw);
    double mc = num / den;
    return mc >= 0.0 ? mc : kMaxChroma;
}

double clipWhite(const double co[3], double tw, double y, double cosH, double sinH) {
    const double eps = 0.001;
    double maxY = kY1931 * tw, dY = std::max(maxY - y, 0.0);
    double mc = dY < eps ? dY / std::max(eps * maxY, 1e-8) * clipWhiteRaw(co, tw, (1.0 - eps) * maxY, cosH, sinH)
                         : clipWhiteRaw(co, tw, y, cosH, sinH);
    return mc >= 0.0 ? mc : kMaxChroma;
}

double clipBlack(const double co[3], double cosH, double sinH) {
    double den = co[0] * (0.979381443298969 * cosH + 0.391752577319588 * sinH) +
                 co[1] * (0.0206185567010309 * cosH + 0.608247422680412 * sinH) - co[2] * (cosH + sinH);
    if (std::fabs(den) <= 1e-8) return kMaxChroma;
    double mc = -0.427506877216495 * (co[0] + 0.856492345150334 * co[1] + 0.554995960637719 * co[2]) / den;
    return mc >= 0.0 ? mc : kMaxChroma;
}

void gamutCheckRgb(const Ych &in, double out[3]) {
    double rgb[3];
    ychToRgb(in, true, rgb);
    double offset = std::max(-std::min(rgb[0], std::min(rgb[1], rgb[2])), 0.0);
    for (double &v : rgb) v += offset;
    Ych b = rgbToYch(rgb, true);
    double y = std::min(std::max(0.5 * (in.y + b.y), kY1931 * kDisplayBlack), kY1931);
    double cw = kMaxChroma, cb = kMaxChroma;
    for (int r = 0; r < 3; r++) {
        cw = std::min(cw, clipWhite(kLmsToSrgb + 3 * r, 1.0, y, in.cosH, in.sinH));
        cb = std::min(cb, clipBlack(kLmsToSrgb + 3 * r, in.cosH, in.sinH));
    }
    double c = std::max(std::min(std::min(in.c, cw), cb), 0.0);
    ychToRgb({y, c, in.cosH, in.sinH}, true, out);
    for (int i = 0; i < 3; i++) out[i] = std::min(std::max(out[i], 0.0), 1.0);
}

}  // namespace

void sigmoid(const SigmoidParams &p, const double in[3], double out[3]) {
    constexpr double white = 1.0, fog = 0.00142637086, greyTarget = 0.1845;
    const double filmPower = p.contrast, hue = p.hue;
    const double paperExposure = std::pow(fog + greyTarget, filmPower) * (1.0 / greyTarget - 1.0);
    double avg = std::max((in[0] + in[1] + in[2]) / 3.0, 0.0);
    double mn = std::min(in[0], std::min(in[1], in[2]));
    double sat = mn < 0.0 ? -avg / (mn - avg) : 1.0;
    double c[3], per[3];
    for (int i = 0; i < 3; i++) {
        c[i] = avg + sat * (in[i] - avg);
        double film = std::pow(fog + std::max(c[i], 0.0), filmPower);
        per[i] = std::min(std::max(white * (film / (paperExposure + film)), 0.0), white);
    }
    int o0, o1, o2;  // sigmoidChannelOrder: min, mid, max
    if (c[0] >= c[1]) {
        if (c[1] > c[2]) o0 = 2, o1 = 1, o2 = 0;
        else if (c[2] > c[0]) o0 = 1, o1 = 0, o2 = 2;
        else if (c[2] > c[1]) o0 = 1, o1 = 2, o2 = 0;
        else o0 = 2, o1 = 1, o2 = 0;
    } else if (c[0] >= c[2]) {
        o0 = 2, o1 = 0, o2 = 1;
    } else if (c[2] > c[1]) {
        o0 = 0, o1 = 1, o2 = 2;
    } else {
        o0 = 0, o1 = 2, o2 = 1;
    }
    double iMin = c[o0], iMid = c[o1], iMax = c[o2], pMin = per[o0], pMid = per[o1], pMax = per[o2];
    double chroma = iMax - iMin;
    double midScale = chroma != 0.0 ? (iMid - iMin) / chroma : 0.0;
    double full = pMin + (pMax - pMin) * midScale;
    double naiveMid = pMid + (full - pMid) * hue;
    double perEnergy = per[0] + per[1] + per[2];
    double naiveEnergy = pMin + naiveMid + pMax;
    double minMid = iMin + iMid;
    double blend = minMid != 0.0 ? 2.0 * iMin / minMid : 0.0;
    double target = blend * perEnergy + (1.0 - blend) * naiveEnergy;
    double outMin, outMid, outMax;
    if (naiveMid <= pMid) {
        double m = ((1.0 - hue) * pMid + hue * (midScale * pMax + (1.0 - midScale) * (target - pMax))) /
                   (1.0 + hue * (1.0 - midScale));
        outMin = target - pMax - m, outMid = m, outMax = pMax;
    } else {
        double m = ((1.0 - hue) * pMid + hue * (pMin * (1.0 - midScale) + midScale * (target - pMin))) /
                   (1.0 + hue * midScale);
        outMin = pMin, outMid = m, outMax = target - pMin - m;
    }
    out[o0] = outMin, out[o1] = outMid, out[o2] = outMax;
}

namespace {

// Solves a x = b (5 x 5, partial pivoting).
void solve5(double a[5][5], double b[5], double x[5]) {
    for (int i = 0; i < 5; i++) {
        int pivot = i;
        for (int r = i + 1; r < 5; r++)
            if (std::fabs(a[r][i]) > std::fabs(a[pivot][i])) pivot = r;
        for (int c = 0; c < 5; c++) std::swap(a[i][c], a[pivot][c]);
        std::swap(b[i], b[pivot]);
        for (int r = i + 1; r < 5; r++) {
            double f = a[r][i] / a[i][i];
            for (int c = i; c < 5; c++) a[r][c] -= f * a[i][c];
            b[r] -= f * b[i];
        }
    }
    for (int i = 4; i >= 0; i--) {
        double s = b[i];
        for (int c = i + 1; c < 5; c++) s -= a[i][c] * x[c];
        x[i] = s / a[i][i];
    }
}

}  // namespace

FilmicUniforms filmicUniforms(double blackIn, double whiteIn) {
    constexpr double kMinDr = 0.2, kDefaultDr = 12.21, kDefaultContrast = 1.433801098, kLatitude = 0.0001, kSafety = 0.01;
    double black = std::min(blackIn, whiteIn - kMinDr);
    double white = std::max(whiteIn, black + kMinDr);
    double dr = std::max(kMinDr, white - black);
    double inMin = std::pow(2.0, black) * kGreySource, inMax = std::pow(2.0, white) * kGreySource;
    double bd = std::pow(kDisplayBlack, 1.0 / kOutputPower), wd = 1.0, gd = std::pow(kGreySource, 1.0 / kOutputPower);
    double gl = std::min(std::max(std::fabs(black) / dr, 0.001), 0.999);
    double contrast = kDefaultContrast * (dr / kDefaultDr);
    double minc = std::max(1.0, std::max((wd - gd) / std::max(1.0 - gl, 1e-5), (gd - bd) / std::max(gl, 1e-5)));
    contrast = std::min(std::max(contrast, minc + kSafety), 100.0);
    double li = gd - contrast * gl, drange = wd - bd;
    double xmin = (bd + kSafety * drange - li) / contrast, xmax = (wd - kSafety * drange - li) / contrast;
    double toeLog = std::min(std::max((1.0 - kLatitude) * gl + kLatitude * xmin, 0.0), gl);
    double shLog = std::min(std::max((1.0 - kLatitude) * gl + kLatitude * xmax, gl), 1.0);
    double toeD = toeLog * contrast + li, shD = shLog * contrast + li;
    double ta[5][5] = {{0, 0, 0, 0, 1}, {0, 0, 0, 1, 0},
                       {std::pow(toeLog, 4), std::pow(toeLog, 3), toeLog * toeLog, toeLog, 1},
                       {4 * std::pow(toeLog, 3), 3 * toeLog * toeLog, 2 * toeLog, 1, 0},
                       {12 * toeLog * toeLog, 6 * toeLog, 2, 0, 0}};
    double tb[5] = {bd, 0, toeD, contrast, 0}, toe[5];
    solve5(ta, tb, toe);
    double sa[5][5] = {{1, 1, 1, 1, 1}, {4, 3, 2, 1, 0},
                       {std::pow(shLog, 4), std::pow(shLog, 3), shLog * shLog, shLog, 1},
                       {4 * std::pow(shLog, 3), 3 * shLog * shLog, 2 * shLog, 1, 0},
                       {12 * shLog * shLog, 6 * shLog, 2, 0, 0}};
    double sb[5] = {wd, 0, shD, contrast, 0}, sh[5];
    solve5(sa, sb, sh);
    FilmicUniforms u;
    u.black = float(black);
    u.dynamicRange = float(dr);
    u.inputMin = float(std::max(inMin, 1e-8));
    u.inputMax = float(std::max(inMax, inMin + 1e-8));
    u.latitudeMin = float(toeLog);
    u.latitudeMax = float(shLog);
    for (int r = 0; r < 5; r++) {  // rows M1..M5 are the coefficients of x^0..x^4
        u.m[r][0] = float(toe[4 - r]);
        u.m[r][1] = float(sh[4 - r]);
        u.m[r][2] = 0.0f;
    }
    u.m[0][2] = float(toeD - contrast * toeLog);
    u.m[1][2] = float(contrast);
    return u;
}

void agx(const AgxParams &p, const double in[3], double out[3]) {
    AgxCurve curve = agxCurveFor(p);
    double c[3];
    for (int i = 0; i < 3; i++) c[i] = std::isnan(in[i]) ? 0.0 : std::min(std::max(in[i], -1000000.0), 1000000.0);
    double base[3], rend[3], before[3], after[3], t[3];
    agxCompress(c, base);
    mul3(kAgxBaseToRender, base, rend);
    rgbToHsv(rend, before);
    for (int i = 0; i < 3; i++) t[i] = std::pow(std::max(agxCurve(curve, agxLog(curve, rend[i])), 0.0), kAgxGamma);
    rgbToHsv(t, after);
    double d = after[0] - before[0];
    double shortest = d - std::round(d);
    double mixed = (1.0 - kAgxHueMix) * shortest + before[0];
    after[0] = mixed - std::floor(mixed);
    hsvToRgb(after, t);
    mul3(kAgxRenderToBase, t, out);
}

void filmic(const FilmicUniforms &u, const double in[3], double out[3]) {
    double toned[3];
    double pos[3] = {std::max(in[0], 0.0), std::max(in[1], 0.0), std::max(in[2], 0.0)};
    double mx = std::max(pos[0], std::max(pos[1], pos[2]));
    double ratioNorm = std::max(mx, double(u.inputMin));
    double toneNorm = std::min(std::max(mx, double(u.inputMin)), double(u.inputMax));
    double norm = std::pow(std::max(std::min(std::max(filmicSpline(u, filmicLogEncode(u, toneNorm)), kDisplayBlack), 1.0), 0.0),
                           kOutputPower);
    for (int i = 0; i < 3; i++) {
        double naive = std::pow(std::max(std::min(std::max(filmicSpline(u, filmicLogEncode(u, std::max(in[i], kNormMin))), 0.0), 1.0), 0.0),
                                kOutputPower);
        toned[i] = 0.5 * naive + 0.5 * (pos[i] / ratioNorm) * norm;
    }
    // darktableFilmicGamutMapV5
    Ych yo = rgbToYch(in, false), yf = rgbToYch(toned, false);
    yf.c = std::min(yo.c, yf.c);
    yf.cosH = yo.cosH, yf.sinH = yo.sinH;
    yf.y = std::min(std::max(yf.y, kY1931 * kDisplayBlack), kY1931);
    double co = yo.c * yo.y, cf = yf.c * yf.y;
    if (yf.y > yo.y && co < cf) cf = 0.5 * (co + cf);  // desaturate v4, saturation 0
    yf.c = std::max(cf / std::max(yf.y, 1e-8), 0.0);
    double yr = yf.c * yf.cosH + kYrgR, yg = yf.c * yf.sinH + kYrgG;  // gamut check yrg
    double mc = std::max(yf.c, 0.0);
    if (yr < 0.0 && std::fabs(yf.cosH) > 1e-8) mc = std::min(-kYrgR / yf.cosH, mc);
    if (yg < 0.0 && std::fabs(yf.sinH) > 1e-8) mc = std::min(-kYrgG / yf.sinH, mc);
    if (yr + yg > 1.0 && std::fabs(yf.cosH + yf.sinH) > 1e-8) mc = std::min((1.0 - kYrgR - kYrgG) / (yf.cosH + yf.sinH), mc);
    yf.c = std::max(mc, 0.0);
    double srgb[3], lms[3];
    gamutCheckRgb(yf, srgb);
    mul3(kSrgbToLms, srgb, lms);
    mul3(kLmsToBt2020, lms, out);
}

}  // namespace dt
}  // namespace mastercolor
