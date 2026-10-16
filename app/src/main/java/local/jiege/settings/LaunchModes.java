package local.jiege.settings;

/**
 * Modes the quick launch key can open (ModuleSettings.KEY_LAUNCH_MODE): the camera's mode names
 * (mode_data.db mode_name on PMA110) with their labels in the camera.
 */
public final class LaunchModes {
    public static final String DEFAULT = "gr";

    public static final String[] NAMES = {
        "common", "portrait", "commonVideo", "professional", "gr", "retroCamera", "highPixel",
        "night", "telephoto", "xpan", "tiltShift", "masterVideo", "longExposure", "panorama", "slowVideo", "fastVideo",
    };
    public static final String[] LABELS = {
        "照片", "人像", "视频", "大师", "理光 GR", "POP", "哈苏超清",
        "夜景", "长焦", "XPAN", "移轴", "大师视频", "长曝光", "全景", "慢动作", "延时摄影",
    };

    private LaunchModes() {}

    public static String label(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return LABELS[i];
        return name;
    }
}
