package local.jiege.hook.gr;

import java.lang.reflect.Method;
import local.jiege.hook.common.Symbols;

/** State shared by the GR hooks in the camera process. */
final class GrState {
    /** GrSupport.isGrMode(): the camera's own "current mode is GR" check. Null until GR hooks are installed. */
    private static volatile Method grModeCheck;
    /**
     * isGrMode() reads the stored mode through DataManager, whose read hooks ask isGrMode() again.
     * A nested call answers with the outer call's last result instead of recursing.
     */
    private static final ThreadLocal<Boolean> checking = new ThreadLocal<>();
    private static volatile boolean lastGrMode;
    /** Latest GR mode instance seen by the hooks. */
    static volatile Object currentMode;
    /** pref_snap_switch_key value: off, 1m, 2.5m, 5m or ∞. */
    static volatile String snapSetting = "off";
    /** GR watermark style chosen in the gallery editor. */
    static volatile String watermarkStyleId = "gr_style_1";

    private GrState() {}

    static void init(Symbols symbols) {
        grModeCheck = symbols.method("GrSupport.isGrMode");
    }

    static boolean isGrMode() {
        Method method = grModeCheck;
        if (method == null) return false;
        if (Boolean.TRUE.equals(checking.get())) return lastGrMode;
        checking.set(Boolean.TRUE);
        try {
            boolean grMode = Boolean.TRUE.equals(method.invoke(null));
            lastGrMode = grMode;
            return grMode;
        } catch (Throwable error) {
            return false;
        } finally {
            checking.remove();
        }
    }

    static boolean snapActive() {
        return snapSetting != null && !"off".equalsIgnoreCase(snapSetting);
    }
}
