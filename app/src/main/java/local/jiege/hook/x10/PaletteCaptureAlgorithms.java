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
            if (insertion == algorithms.length && ("aps_algo_face_info".equals(algorithms[i])
                    || "aps_algo_post_proc".equals(algorithms[i]))) insertion = i;
        }
        String[] result = new String[algorithms.length + 1];
        System.arraycopy(algorithms, 0, result, 0, insertion);
        result[insertion] = PALETTE;
        System.arraycopy(algorithms, insertion, result, insertion + 1, algorithms.length - insertion);
        return result;
    }
}
