package local.jiege.settings;

/**
 * Exposure alignment of a master look to this phone's stock master look (LMTLut3): an EV offset added to the
 * exposure compensation. The offsets come from MasterAlignTable (tools/mastercolor/align_table.py). A look
 * that cannot reach the stock value with any exposure, or that is not in the table, does not offer that alignment.
 */
public final class MasterAlign {
    public static final int NONE = 0;
    public static final int GREY = 1;
    public static final int MID = 2;
    public static final int HIGH = 3;
    public static final int WHITE = 4;

    public static final String[] LABELS = {"不对齐", "中灰", "中间调", "高光", "白点"};
    public static final String[] SUMMARIES = {
        "保持所选色彩自身的曝光",
        "18% 灰的输出与原厂一致",
        "中间调的平均明度与原厂一致",
        "高光的平均明度与原厂一致",
        "90% 漫反射白的输出与原厂一致",
    };

    private MasterAlign() {}

    /** True if the look is in the table at all (the stock look and the not covered variants are not). */
    public static boolean covered(String look) {
        return MasterAlignTable.OFFSETS.containsKey(look);
    }

    public static boolean available(String look, int mode) {
        if (mode == NONE) return true;
        float[] offsets = MasterAlignTable.OFFSETS.get(look);
        return offsets != null && mode <= offsets.length && !Float.isNaN(offsets[mode - 1]);
    }

    /** EV to add to the exposure compensation; 0 when the mode is NONE or the look does not offer it. */
    public static float offset(String look, int mode) {
        return available(look, mode) && mode != NONE ? MasterAlignTable.OFFSETS.get(look)[mode - 1] : 0f;
    }
}
