package local.jiege.lut;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Locale;

/**
 * Converts a Rec.709 look LUT (.cube, any 3D size) into the camera's master filter LUT format
 * (.MS-LUT: 176-byte header + 32^3 RGB u8, R fastest). Plain Java so it runs in the settings app
 * and offline; a port of _verification_scripts/bake_rec709_to_serenity_mslut.py (lut_eotf srgb,
 * gamut css4), which baked the module's LUTs.
 *
 * The device samples the LUT with Display P3 primaries and the sRGB EOTF. Each lattice point is
 * taken P3 -> Rec.709 (CSS Color 4 binary-search gamut mapping in OkLCh, relative colorimetric),
 * run through the look (trilinear, in Rec.709 sRGB encoding), and taken back Rec.709 -> P3.
 */
public final class Rec709LutBaker {
    public static final int N = 32;
    public static final int HEADER_SIZE = 176;
    public static final int PAYLOAD = N * N * N * 3;
    public static final int TOTAL = HEADER_SIZE + PAYLOAD;

    private static final double[][] M_709_TO_XYZ = {
        {0.4124564, 0.3575761, 0.1804375},
        {0.2126729, 0.7151522, 0.0721750},
        {0.0193339, 0.1191920, 0.9503041},
    };
    private static final double[][] M_P3_TO_XYZ = {
        {0.4865709, 0.2656677, 0.1982173},
        {0.2289746, 0.6917385, 0.0792869},
        {0.0000000, 0.0451134, 1.0439444},
    };
    private static final double[][] M_XYZ_TO_709 = invert(M_709_TO_XYZ);
    private static final double[][] M_XYZ_TO_P3 = invert(M_P3_TO_XYZ);
    private static final double[][] XYZ_TO_LMS = {
        {0.8190224379967030, 0.3619062600528904, -0.1288737815209879},
        {0.0329836539323885, 0.9292868615863434, 0.0361446663506424},
        {0.0481771893596242, 0.2642395317527308, 0.6335478284694309},
    };
    private static final double[][] LMS_TO_OKLAB = {
        {0.2104542683093140, 0.7936177747023054, -0.0040720430116193},
        {1.9779985324311684, -2.4285922420485799, 0.4505937096174110},
        {0.0259040424655478, 0.7827717124575296, -0.8086757549230774},
    };
    private static final double[][] OKLAB_TO_LMS = {
        {1.0000000000000000, 0.3963377773761749, 0.2158037573099136},
        {1.0000000000000000, -0.1055613458156586, -0.0638541728258133},
        {1.0000000000000000, -0.0894841775298119, -1.2914855480194092},
    };
    private static final double[][] LMS_TO_XYZ = {
        {1.2268798758459243, -0.5578149944602171, 0.2813910456659647},
        {-0.0405757452148008, 1.1122868032803170, -0.0717110580655164},
        {-0.0763729366746601, -0.4214933324022432, 1.5869240198367816},
    };
    private static final double JND = 0.02;
    private static final double EPS = 1e-4;
    private static final double SLACK = 1e-6;

    /** A parsed .cube: table[b][g][r] = {r, g, b}, plus its domain. */
    public static final class Cube {
        final int size;
        final double[][][][] table;
        final double[] min;
        final double[] max;

        Cube(int size, double[][][][] table, double[] min, double[] max) {
            this.size = size;
            this.table = table;
            this.min = min;
            this.max = max;
        }

        public int size() {
            return size;
        }
    }

    private Rec709LutBaker() {}

    public static Cube parse(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        int size = -1;
        double[] min = {0, 0, 0};
        double[] max = {1, 1, 1};
        double[] rows = null;
        int count = 0;
        for (String raw; (raw = reader.readLine()) != null; ) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.replace(',', ' ').split("\\s+");
            String key = parts[0].toUpperCase(Locale.ROOT);
            if (key.equals("TITLE")) continue;
            if (key.equals("LUT_3D_SIZE")) {
                size = Integer.parseInt(parts[1]);
                if (size < 2 || size > 256) throw new IOException("Unsupported LUT_3D_SIZE " + size);
                rows = new double[size * size * size * 3];
                continue;
            }
            if (key.equals("DOMAIN_MIN")) { min = triple(parts); continue; }
            if (key.equals("DOMAIN_MAX")) { max = triple(parts); continue; }
            if (key.startsWith("LUT_") || key.equals("1D") || key.equals("3D")) continue;
            if (parts.length >= 3) {
                if (rows == null) throw new IOException("Data before LUT_3D_SIZE");
                if (count >= rows.length / 3) throw new IOException("Too many LUT rows");
                for (int c = 0; c < 3; c++) rows[count * 3 + c] = Double.parseDouble(parts[c]);
                count++;
            }
        }
        if (rows == null) throw new IOException("Missing LUT_3D_SIZE");
        if (count != size * size * size) throw new IOException("Got " + count + " rows, expected " + size * size * size);
        double[][][][] table = new double[size][size][size][];
        int i = 0;
        for (int b = 0; b < size; b++) for (int g = 0; g < size; g++) for (int r = 0; r < size; r++, i++) {
            table[b][g][r] = new double[] {rows[i * 3], rows[i * 3 + 1], rows[i * 3 + 2]};
        }
        return new Cube(size, table, min, max);
    }

    /** The full MS-LUT file: header (from an existing device LUT) + baked payload. */
    public static byte[] bake(Cube cube, byte[] header) {
        if (header.length < HEADER_SIZE) throw new IllegalArgumentException("Header too short");
        byte[] out = new byte[TOTAL];
        System.arraycopy(header, 0, out, 0, HEADER_SIZE);
        double[] p3 = new double[3];
        int index = 0;
        for (int b = 0; b < N; b++) for (int g = 0; g < N; g++) for (int r = 0; r < N; r++) {
            p3[0] = r / (double) (N - 1);
            p3[1] = g / (double) (N - 1);
            p3[2] = b / (double) (N - 1);
            double[] result = bakePoint(cube, p3);
            for (int c = 0; c < 3; c++) out[HEADER_SIZE + index * 3 + c] = (byte) Math.rint(result[c] * 255.0);
            index++;
        }
        return out;
    }

    /** One lattice point, encoded P3 in, encoded P3 out (both [0,1]). */
    static double[] bakePoint(Cube cube, double[] p3Encoded) {
        double[] p3Linear = new double[3];
        for (int c = 0; c < 3; c++) p3Linear[c] = srgbToLinear(p3Encoded[c]);
        double[] recLinear = css4Map(mul(M_P3_TO_XYZ, p3Linear), M_XYZ_TO_709, M_709_TO_XYZ);
        double[] recEncoded = new double[3];
        for (int c = 0; c < 3; c++) {
            double e = clamp(linearToSrgb(recLinear[c]));
            recEncoded[c] = cube.min[c] + e * (cube.max[c] - cube.min[c]);
        }
        double[] look = sample(cube, recEncoded);
        double[] lookLinear = new double[3];
        for (int c = 0; c < 3; c++) {
            double span = Math.max(cube.max[c] - cube.min[c], 1e-12);
            lookLinear[c] = srgbToLinear(clamp((look[c] - cube.min[c]) / span));
        }
        double[] outLinear = css4Map(mul(M_709_TO_XYZ, lookLinear), M_XYZ_TO_P3, M_P3_TO_XYZ);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) out[c] = clamp(linearToSrgb(outLinear[c]));
        return out;
    }

    // ---- CSS Color 4 gamut mapping (binary search + local MINDE, OkLCh) -------------------------

    static double[] css4Map(double[] xyz, double[][] xyzToRgb, double[][] rgbToXyz) {
        double[] dest = mul(xyzToRgb, xyz);
        double[] lab = xyzToOklab(xyz);
        double lightness = lab[0];
        if (lightness >= 1.0) return new double[] {1, 1, 1};
        if (lightness <= 0.0) return new double[] {0, 0, 0};
        if (inUnitCube(dest, SLACK)) return dest;
        double a = lab[1], b = lab[2];
        double chroma = Math.sqrt(a * a + b * b);

        double[] full = rgbAt(lightness, a, b, chroma, chroma, xyzToRgb);
        double[] clipped = clip(full);
        if (delta(clipped, oklabAt(lightness, a, b, chroma, chroma), rgbToXyz) < JND) return clipped;

        double minC = 0, maxC = chroma;
        boolean minInGamut = true;
        for (int i = 0; i < 48 && maxC - minC > EPS; i++) {
            double c = 0.5 * (minC + maxC);
            double[] rgb = rgbAt(lightness, a, b, chroma, c, xyzToRgb);
            if (minInGamut && inUnitCube(rgb, 0.0)) {
                minC = c;
                continue;
            }
            double[] clip = clip(rgb);
            double err = delta(clip, oklabAt(lightness, a, b, chroma, c), rgbToXyz);
            if (err < JND) {
                if (JND - err < EPS) return clip;
                minInGamut = false;
                minC = c;
            } else {
                maxC = c;
            }
        }
        return clip(rgbAt(lightness, a, b, chroma, 0.5 * (minC + maxC), xyzToRgb));
    }

    private static double[] oklabAt(double l, double a, double b, double chroma, double c) {
        double scale = chroma > 1e-14 ? c / chroma : 0.0;
        return new double[] {l, a * scale, b * scale};
    }

    private static double[] rgbAt(double l, double a, double b, double chroma, double c, double[][] xyzToRgb) {
        return mul(xyzToRgb, oklabToXyz(oklabAt(l, a, b, chroma, c)));
    }

    private static double delta(double[] clippedRgb, double[] lab, double[][] rgbToXyz) {
        double[] clipLab = xyzToOklab(mul(rgbToXyz, clippedRgb));
        double d0 = clipLab[0] - lab[0], d1 = clipLab[1] - lab[1], d2 = clipLab[2] - lab[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    static double[] xyzToOklab(double[] xyz) {
        double[] lms = mul(XYZ_TO_LMS, xyz);
        for (int i = 0; i < 3; i++) lms[i] = Math.cbrt(lms[i]);
        return mul(LMS_TO_OKLAB, lms);
    }

    static double[] oklabToXyz(double[] lab) {
        double[] lms = mul(OKLAB_TO_LMS, lab);
        for (int i = 0; i < 3; i++) lms[i] = lms[i] * lms[i] * lms[i];
        return mul(LMS_TO_XYZ, lms);
    }

    // ---- LUT sampling ----------------------------------------------------------------------------

    static double[] sample(Cube cube, double[] rgb) {
        int n = cube.size;
        int[] i0 = new int[3];
        int[] i1 = new int[3];
        double[] f = new double[3];
        for (int c = 0; c < 3; c++) {
            double p = clamp(rgb[c]) * (n - 1);
            i0[c] = (int) Math.floor(p);
            i1[c] = Math.min(i0[c] + 1, n - 1);
            f[c] = p - i0[c];
        }
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            double c000 = cube.table[i0[2]][i0[1]][i0[0]][c], c100 = cube.table[i0[2]][i0[1]][i1[0]][c];
            double c010 = cube.table[i0[2]][i1[1]][i0[0]][c], c110 = cube.table[i0[2]][i1[1]][i1[0]][c];
            double c001 = cube.table[i1[2]][i0[1]][i0[0]][c], c101 = cube.table[i1[2]][i0[1]][i1[0]][c];
            double c011 = cube.table[i1[2]][i1[1]][i0[0]][c], c111 = cube.table[i1[2]][i1[1]][i1[0]][c];
            double c00 = c000 * (1 - f[0]) + c100 * f[0];
            double c10 = c010 * (1 - f[0]) + c110 * f[0];
            double c01 = c001 * (1 - f[0]) + c101 * f[0];
            double c11 = c011 * (1 - f[0]) + c111 * f[0];
            double c0 = c00 * (1 - f[1]) + c10 * f[1];
            double c1 = c01 * (1 - f[1]) + c11 * f[1];
            out[c] = c0 * (1 - f[2]) + c1 * f[2];
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------

    static double srgbToLinear(double u) {
        return u <= 0.04045 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4);
    }

    static double linearToSrgb(double u) {
        return u <= 0.0031308 ? 12.92 * u : 1.055 * Math.pow(Math.max(u, 0.0), 1.0 / 2.4) - 0.055;
    }

    private static boolean inUnitCube(double[] rgb, double slack) {
        for (double v : rgb) if (v < -slack || v > 1.0 + slack) return false;
        return true;
    }

    private static double[] clip(double[] rgb) {
        return new double[] {clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2])};
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double[] triple(String[] parts) {
        return new double[] {Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
    }

    private static double[] mul(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
        };
    }

    private static double[][] invert(double[][] m) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
            - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
            + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        double[][] inv = new double[3][3];
        inv[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / det;
        inv[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / det;
        inv[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / det;
        inv[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / det;
        inv[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / det;
        inv[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / det;
        inv[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / det;
        inv[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / det;
        inv[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / det;
        return inv;
    }
}
