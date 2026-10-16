// Port of PhotonCamera-bjzhou raw/CanonToneAlgorithm.kt (GLSL canonRecipe / canonYuvToRgb /
// applyEngineTone) and CanonIccShader.kt, stage by stage; see tools/mastercolor/engines_canon.py.
#include "master_canon.h"

#include <algorithm>
#include <cmath>

namespace mastercolor {
namespace canon {
namespace {

using I = int64_t;

inline I clampI(I v, I lo, I hi) { return std::min(std::max(v, lo), hi); }

struct Recipe {
    const Style &s;
    I w(int i) const { return s.words[size_t(i)]; }
    I look(I i) const { return s.atlas[size_t(i)]; }
    I cw(int i) const { return w(0x122 + i); }

    static I sector(I code, I first, I second) {
        I quadrant = (code >> 5) & 3;
        I u = quadrant < 2 ? ~first : first;
        I v = quadrant == 1 || quadrant == 2 ? ~second : second;
        I angle = code & 63;
        I a = angle > 31 ? 64 - angle : angle;
        I b = angle > 31 ? angle - 32 : 32 - angle;
        I value = a * u + b * v;
        I mode = (code >> 8) & 7, r = value;
        if (mode == 0) r = (value * 3) >> 6;
        else if (mode == 1) r = value >> 4;
        else if (mode == 2) r = (value * 3) >> 5;
        else if (mode == 3) r = value >> 3;
        else if (mode == 4) r = (value * 3) >> 4;
        else if (mode == 5) r = (((value * 3) >> 1) + value * 3) >> 4;
        else if (mode == 6) r = value >> 1;
        return clampI(r, 0, 1023);
    }

    static I radial(I value, I code) {
        I mode = (code >> 11) & 7, r = value * 8;
        if (mode == 0) r = (value * 3) >> 2;
        else if (mode == 1) r = value;
        else if (mode == 2) r = (value >> 2) + value;
        else if (mode == 3) r = (value * 3) >> 1;
        else if (mode == 4) r = value * 2;
        else if (mode == 5) r = value * 3;
        else if (mode == 6) r = ((value * 3) >> 1) + value * 3;
        return std::min<I>(1023, r);
    }

    static I shift(I value, I amount) { return amount < 0 ? value >> (-amount & 31) : value << (amount & 31); }

    void correctChroma(I u, I v, I luma, I &ou, I &ov) const {
        I li = luma >> 4;
        I delta = li - cw(8);
        I threshold = cw(3) & 2047;
        if (cw(4) != 0) {
            threshold = std::min<I>(2047, delta < 0 ? ((cw(5) * li) >> 7) + threshold
                                                    : ((cw(6) * clampI(delta, 0, 1023)) >> 7) + cw(7));
        }
        I sa = sector(cw(21), u >> 4, v >> 4), sb = sector(cw(22), u >> 4, v >> 4);
        I sw = (cw(21) & 128) != 0 ? std::max(sa, sb) : std::min(sa, sb);
        I ru = clampI(shift(u, cw(25) - 4), -1024, 1023), rv = clampI(shift(v, cw(25) - 4), -1024, 1023);
        ru = ru < 0 ? ~ru : ru;
        rv = rv < 0 ? ~rv : rv;
        I radius = std::max(ru + rv * 2, rv + ru * 2);
        I inner = radial(clampI(radius - threshold * 2, 0, 2047), cw(23));
        I outer = radial(clampI((cw(24) & 2047) * 2 - radius, 0, 2047), cw(24));
        I light = clampI(shift(luma, cw(28) - 4), 0, 1023);
        I lower = std::min<I>(clampI(light * 2 - (cw(26) & 2047), 0, 1023) << ((cw(26) >> 11) & 3), 1023);
        I upper = std::min<I>(clampI((cw(27) & 2047) - light * 2, 0, 1023) << ((cw(27) >> 11) & 3), 1023);
        I weight = std::min(std::min(sw, std::min(inner, outer)), std::min(lower, upper));
        I gu = (cw(49) * weight) >> 7, gv = (cw(59) * weight) >> 7;
        ou = clampI(u + ((u * gu) >> 10), -16384, 16383) * 2;
        ov = clampI(v + ((v * gv) >> 10), -16384, 16383) * 2;
    }

    I y(I value) const {
        I code = value * w(2) / w(3);
        code = clampI(std::min<I>(code, 131071), 0, w(0x68));
        return look(code);
    }

    I c(I value, I mono) const {
        I code = clampI(value, -131071, 131071);
        if (w(0xf) == 1) code += mono;
        if (code < 0) return 2 * look(131072) - look(131072 - code);
        return look(131072 + std::min(code, w(0x86)));
    }

    I weightIndex(I value, I limit) const { return std::min(value * w(0x76) * 4 / 4096, limit); }

    void run(I red, I green, I blue, I yuv[3]) const {
        I scale = w(2), divisor = w(3), preLimit = w(0x84);
        I b = std::min(blue * 2 * scale / divisor, preLimit);
        I g = std::min(green * 2 * scale / divisor, preLimit);
        I r = std::min(red * 2 * scale / divisor, preLimit);
        I ri = weightIndex(r, w(0x70)), gi = weightIndex(g, w(0x72)), bi = weightIndex(b, w(0x74));
        I li = clampI((gi + bi * 2 + ri) / 128, 0, 1023);
        I di = (gi - ri) / 32;
        I dj = clampI((gi - bi) / 32, 0, 1023);
        b = std::min(b, w(0x6e));
        g = std::min(g, w(0x6c));
        r = std::min(r, w(0x6a));

        I aux = (red * 0x4c8 + green * 0x964 + blue * 0x1d4) / 4096;
        I iu = (blue * 0x800 - green * 0x54c - red * 0x2b3) / 4096;
        I iv = (red * 0x800 - green * 0x6b2 - blue * 0x14d) / 4096;
        I rb = (aux * 0xfff + 0x800 + iu * 0x1c56) / 4096 * 2;
        I rg = (aux * 0x1002 - iv * 0xb6d - iu * 0x584 + 0x800) / 4096 * 2;
        I rr = ((iv * 0xb37 + aux * 0x800 - iu) * 2 + 0x800) / 4096 * 2;
        I yv = clampI((y(rr) * w(0x2a) + y(rg) * w(0x2b) + y(rb) * w(0x2c) + 0x800) / 4096, 0, 65535);

        I fd = clampI((w(0x12) * (b - g) + w(0x10) * (r - g)) / 1024, -131072, 131072);
        I sd = clampI((w(0x1a) * (b - g) + w(0x18) * (r - g)) / 1024, -131072, 131072);
        I dbase = di >= 0 ? 329728 : 327680;
        I weight = std::min(look(328704 + li), look(dbase + std::min<I>(std::abs(di), 1023)));
        weight = std::min(weight, look(330752 + dj));
        I fgain = w(fd > 0 ? 0x25 : 0x26), sgain = w(sd > 0 ? 0x23 : 0x24);
        I foff = std::min((weight * fd / 1024) * fgain / 128, w(0x88));
        I soff = std::min((weight * sd / 1024) * sgain / 128, w(0x88));
        I base = (w(0x1d) * g + w(0x1e) * b + w(0x1c) * r) / 1024;
        I cFirst = base + foff, cThird = base + soff;
        I cSecond = ((base * 9 - cThird) * 0x600 - cFirst * 0x1000) / 0x2000;
        I c1 = c(cFirst, w(0x2d)), c2 = c(cSecond, w(0x2e)), c3 = c(cThird, w(0x2f));
        I d1 = clampI((c3 * 0x800 - c2 * 0x550 - c1 * 0x2b0) / 4096, -32768, 32767);
        I d2 = clampI((c1 * 0x800 - c2 * 0x6b0 - c3 * 0x150) / 4096, -32768, 32767);
        I dom = std::abs(d1) < std::abs(d2) ? d2 : d1;
        I gain = look(262144 + dom + 32768);
        I first = gain * d1 / 256, second = gain * d2 / 256;
        I k1 = w(0x21) + (second <= 0 ? w(0x1f) : 0);
        I k2 = w(0x22) + (first <= 0 ? w(0x20) : 0);
        I mf = clampI(first + k1 * second / 8192, -32768, 32767);
        I ms = clampI(second + k2 * first / 8192, -32768, 32767);
        I w163 = w(0x163), w164 = w(0x164);
        auto lo16 = [](I x) { return ((x & 0xffff) ^ 0x8000) - 0x8000; };
        I cwt = lo16(w163) * c1 + (w163 >> 16) * c2 + lo16(w164) * c3;
        I colorLuma = (cwt + ((cwt >> 31) & 63)) >> 8;
        I u, v;
        correctChroma(mf >> 1, ms >> 1, colorLuma, u, v);
        yuv[0] = (yv >> 2) * 4, yuv[1] = u, yuv[2] = v;
    }
};

// ---------------------------------------------------------------- ICC
double iccCurve(const float *curve, uint32_t n, double v) {
    double p = std::min(std::max(v, 0.0), 1.0) * double(n - 1);
    uint32_t i = std::min(uint32_t(p), n - 2);
    return curve[i] + (curve[i + 1] - curve[i]) * (p - double(i));
}

void iccTetra(const Icc &icc, const double rgb[3], double out[3]) {
    int g = int(icc.grid);
    double p[3], f[3];
    int base[3];
    for (int c = 0; c < 3; c++) {
        p[c] = std::min(std::max(rgb[c], 0.0), 1.0) * double(g - 1);
        base[c] = std::min(int(p[c]), g - 2);
        f[c] = p[c] - base[c];
    }
    int a[3], b[3];
    double wx, wy, wz;
    auto set = [](int *d, int x, int y, int z) { d[0] = x, d[1] = y, d[2] = z; };
    if (f[0] >= f[1]) {
        if (f[1] >= f[2]) set(a, 1, 0, 0), set(b, 1, 1, 0), wx = f[0], wy = f[1], wz = f[2];
        else if (f[0] >= f[2]) set(a, 1, 0, 0), set(b, 1, 0, 1), wx = f[0], wy = f[2], wz = f[1];
        else set(a, 0, 0, 1), set(b, 1, 0, 1), wx = f[2], wy = f[0], wz = f[1];
    } else {
        if (f[0] >= f[2]) set(a, 0, 1, 0), set(b, 1, 1, 0), wx = f[1], wy = f[0], wz = f[2];
        else if (f[1] >= f[2]) set(a, 0, 1, 0), set(b, 0, 1, 1), wx = f[1], wy = f[2], wz = f[0];
        else set(a, 0, 0, 1), set(b, 0, 1, 1), wx = f[2], wy = f[1], wz = f[0];
    }
    auto corner = [&](int dr, int dg, int db, int c) {
        return double(icc.clut[((size_t(base[0] + dr) * g + (base[1] + dg)) * g + (base[2] + db)) * 3 + c]);
    };
    for (int c = 0; c < 3; c++) {
        out[c] = corner(0, 0, 0, c) * (1 - wx) + corner(a[0], a[1], a[2], c) * (wx - wy) +
                 corner(b[0], b[1], b[2], c) * (wy - wz) + corner(1, 1, 1, c) * wz;
    }
}

double iccInverseTrc(const std::vector<float> &t, double v) {
    if (v <= t.front()) return 0.0;
    if (v >= t.back()) return 1.0;
    size_t lo = 0, hi = t.size() - 1;
    for (int step = 0; step < 16 && hi - lo > 1; ++step) {
        size_t mid = (lo + hi) / 2;
        if (t[mid] <= v) lo = mid; else hi = mid;
    }
    double a = t[lo], b = t[hi];
    double fr = b > a ? (v - a) / (b - a) : 0.0;
    return (double(lo) + fr) / double(t.size() - 1);
}

double srgbDecode(double v) { return v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); }

void iccToLinearSrgb(const Icc &icc, const double rgb[3], double out[3]) {
    double shaped[3], pcs[3];
    for (int c = 0; c < 3; c++) shaped[c] = iccCurve(icc.inputCurves.data() + size_t(c) * icc.n, icc.n, rgb[c]);
    iccTetra(icc, shaped, pcs);
    for (int c = 0; c < 3; c++) pcs[c] = iccCurve(icc.outputCurves.data() + size_t(c) * icc.m, icc.m, pcs[c]);
    double fy = (pcs[0] * (65535.0 / 65280.0) * 100.0 + 16.0) / 116.0;
    double fx = fy + (pcs[1] * (65535.0 / 256.0) - 128.0) / 500.0;
    double fz = fy - (pcs[2] * (65535.0 / 256.0) - 128.0) / 200.0;
    auto li = [](double v) { return v > 6.0 / 29.0 ? v * v * v : (v - 4.0 / 29.0) * (108.0 / 841.0); };
    double xyz[3] = {li(fx) * icc.white[0], li(fy) * icc.white[1], li(fz) * icc.white[2]};
    for (int c = 0; c < 3; c++) {
        double lin = icc.xyzToTarget[c * 3] * xyz[0] + icc.xyzToTarget[c * 3 + 1] * xyz[1] + icc.xyzToTarget[c * 3 + 2] * xyz[2];
        out[c] = srgbDecode(iccInverseTrc(icc.trc[c], lin));
    }
}

}  // namespace

void render(const Style &s, const double camera[3], double out[3]) {
    I code[3];
    for (int c = 0; c < 3; c++) code[c] = I(std::min(std::max(camera[c] * 16383.0, 0.0), 65535.0));
    I yuv[3];
    Recipe{s}.run(code[0], code[1], code[2], yuv);
    I y = yuv[0], u = yuv[1], v = yuv[2];
    double enc[3] = {double(clampI(((v * 0xb37 + y * 0x800 - u) * 2 + 0x800) / 4096, 0, 65535)) / 65535.0,
                     double(clampI((y * 0x1002 - v * 0xb6d - u * 0x584 + 0x800) / 4096, 0, 65535)) / 65535.0,
                     double(clampI((y * 0xfff + u * 0x1c56 + 0x800) / 4096, 0, 65535)) / 65535.0};
    if (s.words[0xf] == 1) {
        for (int c = 0; c < 3; c++) out[c] = enc[c] < 0.04045 ? enc[c] / 12.92 : std::pow((enc[c] + 0.055) / 1.055, 2.4);
        return;
    }
    iccToLinearSrgb(s.icc, enc, out);
}

}  // namespace canon
}  // namespace mastercolor
