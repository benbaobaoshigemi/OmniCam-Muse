package local.jiege.hook.x10;

/** Inserts the X10 palette capture algorithm into a capture algorithm list. */
final class PaletteCaptureAlgorithms {
    static final String PALETTE = "aps_algo_color_palette";

    private PaletteCaptureAlgorithms() {}

    static String[] include(String[] algorithms) {
        int insertion = algorithms.length;
        for (int i = 0; i < algorithms.length; i++) {
            if (PALETTE.equals(algorithms[i])) return algorithms;
            // Match APSRefFrameProcessor: insert before the first face-info or post-process node.
            // basic_tone is a second anchor: master/professional mode bakes the tone look into the
            // shot *before* the palette node would otherwise run, so src.image ends up as the
            // already-baked frame and the gallery's color_palette re-render stacks basic_tone twice
            // (the "blurry" 色彩风格 result). Inserting before basic_tone makes src.image the
            // clean pre-tone frame, matching what photo/portrait/high-pixel (which carry no
            // basic_tone) produce naturally.
            if (insertion == algorithms.length && ("aps_algo_face_info".equals(algorithms[i])
                    || "aps_algo_post_proc".equals(algorithms[i])
                    || "aps_algo_basic_tone".equals(algorithms[i]))) insertion = i;
        }
        String[] result = new String[algorithms.length + 1];
        System.arraycopy(algorithms, 0, result, 0, insertion);
        result[insertion] = PALETTE;
        System.arraycopy(algorithms, insertion, result, insertion + 1, algorithms.length - insertion);
        return result;
    }
}
