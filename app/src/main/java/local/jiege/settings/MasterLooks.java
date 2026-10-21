package local.jiege.settings;

/**
 * Master colour (大师色彩) choices shared by the settings UI and the hook. Look ids name the files in
 * files/jiege/mastercolor/looks (tools/mastercolor/looks.py packs them); the order is the persisted index.
 */
public final class MasterLooks {
    /** 模式选择: the chosen look replaces the stock master LMT, or is blended with it (tone / colour weights). */
    public static final int MODE_REPLACE = 0;
    public static final int MODE_BLEND = 1;

    public static final int FAMILY_OPPO = 0;
    public static final int FAMILY_HNCS = 1;
    public static final int FAMILY_LUMIX = 2;
    public static final int FAMILY_FILM = 3;
    public static final int FAMILY_CANON = 4;
    public static final int FAMILY_AGX = 6; // legacy persisted value; now Darktable / AgX
    public static final int FAMILY_DARKTABLE = 7;
    public static final int FAMILY_LEICA = 8;
    public static final int FAMILY_FUJI = 9; // legacy persisted value; no longer offered
    public static final int FAMILY_BASIC = 10; // 基础: the scene front stage followed by a plain transfer curve

    public static final int[] VISIBLE_FAMILIES = {FAMILY_OPPO, FAMILY_BASIC, FAMILY_HNCS, FAMILY_LUMIX, FAMILY_FILM,
        FAMILY_CANON, FAMILY_DARKTABLE, FAMILY_LEICA};
    public static final String[] FAMILY_LABELS = {"OPPO", "基础", "HNCS", "Lumix", "胶片", "Canon", "Darktable", "Leica M9"};
    public static final String[] FAMILY_SUMMARIES = {
        "原厂大师模式色彩",
        "场景色彩经 Adobe Standard 标定后，只套一条传输曲线",
        "哈苏自然色彩解决方案",
        "松下 S9 照片风格",
        "Spektrafilm 胶片模拟",
        "佳能 EOS R5 照片风格",
        "darktable 色调映射",
        "徕卡 M9 矩阵与色调渲染",
    };

    /** OPPO family: the stock master colour of a phone model (機型). Index 0 is the phone itself, the default. */
    public static final String[] OPPO_IDS = {"oppo", "x7u", "x8u"};
    public static final String[] OPPO_LABELS = {"Find X9 Ultra", "Find X7 Ultra", "Find X8 Ultra"};

    public static final String[] LUMIX_IDS = {
        "standard", "vivid", "natural", "landscape", "portrait", "l_classicneo", "709like",
        "monochrome", "l_monochrome", "l_monochrome_d", "l_monochrome_s", "leica_monochrome", "flat", "cinelike_d2", "cinelike_v2",
    };
    public static final String[] LUMIX_LABELS = {
        "标准", "鲜艳", "自然", "风光", "人像", "L.ClassicNeo", "709 Like",
        "单色", "L.单色", "L.单色 D", "L.单色 S", "徕卡单色", "Flat", "Cinelike D2", "Cinelike V2",
    };

    public static final String[] FILM_IDS = {
        "kodak_portra_400",
        "kodak_gold_200",
        "kodak_ektar_100",
        "fujifilm_pro_400h",
        "fujifilm_velvia_100",
        "kodak_kodachrome_64",
        "kodak_vision3_500t",
        "fujifilm_c200",
        "fujifilm_provia_100f",
        "fujifilm_xtra_400",
        "kodak_ektachrome_100",
        "kodak_portra_160",
        "kodak_portra_800",
        "kodak_portra_800_push1",
        "kodak_portra_800_push2",
        "kodak_ultramax_400",
        "kodak_verita_200d",
        "kodak_vision3_200t",
        "kodak_vision3_250d",
        "kodak_vision3_50d"
    };

    public static final String[] FILM_LABELS = {
        "Kodak Portra 400",
        "Kodak Gold 200",
        "Kodak Ektar 100",
        "Fujifilm Pro 400H",
        "Fujifilm Velvia 100",
        "Kodak Kodachrome 64",
        "Kodak Vision3 500T",
        "Fujifilm C200",
        "Fujifilm Provia 100F",
        "Fujifilm Superia X-TRA 400",
        "Kodak Ektachrome 100",
        "Kodak Portra 160",
        "Kodak Portra 800",
        "Kodak Portra 800 Push 1",
        "Kodak Portra 800 Push 2",
        "Kodak UltraMax 400",
        "Kodak Vericolor II 200D",
        "Kodak Vision3 200T",
        "Kodak Vision3 250D",
        "Kodak Vision3 50D"
    };

    public static final String[] FILM_SUMMARIES = {
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "反转片 · 直接扫描",
        "反转片 · 直接扫描",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "反转片 · 直接扫描",
        "负片 · 可选择相纸或电影拷贝",
        "反转片 · 直接扫描",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝",
        "负片 · 可选择相纸或电影拷贝"
    };

    public static final String[] FILM_DEFAULT_PRINTS = {
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "",
        "",
        "kodak_2383",
        "kodak_portra_endura",
        "",
        "kodak_portra_endura",
        "",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_portra_endura",
        "kodak_2383",
        "kodak_2383",
        "kodak_2383"
    };

    public static final String[] PRINT_IDS = {
        "",
        "fujifilm_crystal_archive_typeii",
        "kodak_2383",
        "kodak_2393",
        "kodak_ektacolor_edge",
        "kodak_endura_premier",
        "kodak_portra_endura",
        "kodak_supra_endura",
        "kodak_ultra_endura"
    };

    public static final String[] PRINT_LABELS = {
        "默认搭配",
        "Fujifilm Crystal Archive Type II",
        "Kodak 2383 Color Print",
        "Kodak 2393 Color Print",
        "Kodak Ektacolor Edge",
        "Kodak Endura Premier",
        "Kodak Portra Endura",
        "Kodak Supra Endura",
        "Kodak Ultra Endura"
    };

    public static boolean isPositiveFilm(int film) {
        return FILM_DEFAULT_PRINTS[clamp(film, FILM_IDS.length)].isEmpty();
    }

    public static String filmLookId(int film, int paper) {
        int f = clamp(film, FILM_IDS.length);
        String base = "film_" + FILM_IDS[f];
        if (isPositiveFilm(f)) return base;
        String p = PRINT_IDS[clamp(paper, PRINT_IDS.length)];
        if (p.isEmpty() || p.equals(FILM_DEFAULT_PRINTS[f])) return base;
        return base + "__" + p;
    }

    public static final String[] CANON_IDS = {"standard", "portrait", "landscape", "neutral", "faithful", "monochrome"};
    public static final String[] CANON_LABELS = {"标准", "人像", "风光", "中性", "可靠设置", "单色"};

    public static final String[] HNCS_INTENT_LABELS = {"标准", "复制"};
    public static final String[] HNCS_INTENT_SUMMARIES = {"日常照片的色彩呈现", "用于原物色彩还原的复制映射"};
    public static final String[] HNCS_CURVE_LABELS = {"标准", "复制"};

    public static String hncsLookId(int intent, int curve) {
        int i = clamp(intent, 2), c = clamp(curve, 2);
        if (i == 0 && c == 0) return "hncs";
        return "hncs_" + (i == 0 ? "standard" : "reproduction") + "_" + (c == 0 ? "standard" : "reproduction");
    }

    public static final String[] BASIC_IDS = {"standard", "passthrough", "rec709", "gamma"};
    public static final String[] BASIC_LABELS = {"标准", "直通", "Rec.709", "Gamma"};
    public static final String[] BASIC_SUMMARIES = {
        "Lightroom 渲染 DNG 的默认影调（ACR3 曲线），颜色比例不变",
        "只有线性到显示的转换，没有影调曲线，画面偏平",
        "Rec.709 OETF，暗部更深",
        "幂函数曲线，指数可调（默认 2.4）",
    };

    /**
     * A look parameter the user adjusts: the integer setting that holds it, label, unit, and range / default in
     * setting units (the value shown is setting / scale). The native look parameters are the first four of
     * {@link #paramsFor}, in this order (mastercolor::Key::param).
     */
    public static final class Param {
        public final String key, title, unit;
        public final int scale, min, max, def;

        Param(String key, String title, String unit, int scale, int min, int max, int def) {
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.scale = scale;
            this.min = min;
            this.max = max;
            this.def = def;
        }
    }

    private static final Param[] NO_PARAMS = {};
    private static final Param[] GAMMA_PARAMS = {
        new Param(ModuleSettings.MASTER_BASIC_GAMMA, "Gamma 值", "", 100, 100, 400, 240),
    };
    private static final Param[] SIGMOID_PARAMS = {
        new Param(ModuleSettings.MASTER_SIGMOID_CONTRAST, "对比度", "", 100, 100, 300, 150),
        new Param(ModuleSettings.MASTER_SIGMOID_HUE, "色相保持", "%", 1, 0, 100, 100),
    };
    private static final Param[] FILMIC_PARAMS = {
        new Param(ModuleSettings.MASTER_FILMIC_BLACK, "黑点", " EV", 100, -900, -400, -765),
        new Param(ModuleSettings.MASTER_FILMIC_WHITE, "白点", " EV", 100, 200, 700, 456),
    };
    private static final Param[] AGX_PARAMS = {
        new Param(ModuleSettings.MASTER_AGX_BLACK, "黑点", " EV", 100, -1200, -400, -1000),
        new Param(ModuleSettings.MASTER_AGX_WHITE, "白点", " EV", 100, 400, 1000, 650),
        new Param(ModuleSettings.MASTER_AGX_TOE, "趾部", "", 100, 100, 300, 150),
        new Param(ModuleSettings.MASTER_AGX_SHOULDER, "肩部", "", 100, 100, 500, 330),
    };
    /** Negative film: CMY density in percent (the baker's CMY density, not a look parameter). */
    public static final Param[] FILM_DENSITY_PARAMS = {
        new Param(ModuleSettings.MASTER_FILM_DENSITY_C, "青密度", "%", 1, 50, 150, 100),
        new Param(ModuleSettings.MASTER_FILM_DENSITY_M, "品密度", "%", 1, 50, 150, 100),
        new Param(ModuleSettings.MASTER_FILM_DENSITY_Y, "黄密度", "%", 1, 50, 150, 100),
    };

    /** The adjustable parameters of a look id (empty for looks without any). */
    public static Param[] paramsFor(String lookId) {
        if ("basic_gamma".equals(lookId)) return GAMMA_PARAMS;
        if ("sigmoid".equals(lookId)) return SIGMOID_PARAMS;
        if ("filmic".equals(lookId)) return FILMIC_PARAMS;
        if ("agx".equals(lookId)) return AGX_PARAMS;
        return NO_PARAMS;
    }

    /** Default of a look parameter setting, or null if the key is not one. */
    public static Integer paramDefault(String key) {
        for (Param[] group : new Param[][] {GAMMA_PARAMS, SIGMOID_PARAMS, FILMIC_PARAMS, AGX_PARAMS})
            for (Param p : group)
                if (p.key.equals(key)) return p.def;
        return null;
    }

    public static final String[] DARKTABLE_IDS = {"sigmoid", "filmic", "agx"};
    public static final String[] DARKTABLE_LABELS = {"Sigmoid", "Filmic", "AgX"};

    private MasterLooks() {}

    /** Look id for the native baker: "oppo" is this phone's stock master LMT, "x8u" / "x7u" the Find X8 / X7 Ultra ones. */
    public static String lookId(int family, int oppo, int lumix, int film, int canon, int darktable, int basic, int paper, int hncsIntent, int hncsCurve) {
        switch (family) {
            case FAMILY_OPPO:
                return OPPO_IDS[clamp(oppo, OPPO_IDS.length)];
            case FAMILY_LUMIX:
                return "lumix_" + LUMIX_IDS[clamp(lumix, LUMIX_IDS.length)];
            case FAMILY_FILM:
                return filmLookId(film, paper);
            case FAMILY_CANON:
                return "canon_" + CANON_IDS[clamp(canon, CANON_IDS.length)];
            case FAMILY_AGX:
                return "agx";
            case FAMILY_LEICA:
                return "leica_m9";
            case FAMILY_DARKTABLE:
                return DARKTABLE_IDS[clamp(darktable, DARKTABLE_IDS.length)];
            case FAMILY_BASIC:
                return "basic_" + BASIC_IDS[clamp(basic, BASIC_IDS.length)];
            default:
                return hncsLookId(hncsIntent, hncsCurve);
        }
    }

    private static int clamp(int index, int size) {
        return index < 0 || index >= size ? 0 : index;
    }
}
