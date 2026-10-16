package local.jiege.settings;

/**
 * Settings shared by the settings UI (writer) and the hooks (reader, via LSPosed's
 * xposedsharedprefs). Keys and defaults live only here.
 */
public final class ModuleSettings {
    /** SharedPreferences file name in the module app. */
    public static final String FILE = "module_settings";

    /** POP: let POP use photo mode's 25M output size decision in good light. */
    public static final String POP_HIGH_PIXEL = "pop_high_pixel";
    /** POP captures as SDR (8-bit JPEG) instead of Ultra HDR. */
    public static final String POP_SDR_STYLE = "pop_sdr_style";

    /** Shutter sound: master switch for the two cases below. */
    public static final String SHUTTER_ALWAYS = "shutter_always";
    public static final String SHUTTER_IN_SILENT = "shutter_in_silent";
    public static final String SHUTTER_IN_VIBRATE = "shutter_in_vibrate";
    /** Shutter sound: own volume (SHUTTER_VOLUME percent, on the media stream) in every ringer mode. */
    public static final String SHUTTER_VOLUME_CUSTOM = "shutter_volume_custom";
    /** Integer setting, 0-100. */
    public static final String SHUTTER_VOLUME = "shutter_volume";

    /** Flash and live photo state kept per mode instead of shared by all photo modes. */
    public static final String FLASH_PER_MODE = "flash_per_mode";
    public static final String LIVE_PHOTO_PER_MODE = "live_photo_per_mode";

    /** Master mode: restore effects that the normal-mode version of a filter has. */
    public static final String MASTER_EFFECT_NEON = "master_effect_neon";
    public static final String MASTER_EFFECT_PORTRA = "master_effect_portra";

    /** Master colour (MasterLooks): family index, and the chosen Lumix style / film. Integer settings. */
    public static final String MASTER_LOOK = "master_look";
    public static final String MASTER_LOOK_OPPO = "master_look_oppo";
    public static final String MASTER_HNCS_INTENT = "master_hncs_intent";
    public static final String MASTER_HNCS_CURVE = "master_hncs_curve";
    public static final String MASTER_LOOK_LUMIX = "master_look_lumix";
    public static final String MASTER_LOOK_FILM = "master_look_film";
    public static final String MASTER_LOOK_PRINT = "master_look_print";
    // Backend controls; intentionally not displayed in the settings UI yet. Percent, 50..150.
    public static final String MASTER_FILM_DENSITY_C = "master_film_density_c";
    public static final String MASTER_FILM_DENSITY_M = "master_film_density_m";
    public static final String MASTER_FILM_DENSITY_Y = "master_film_density_y";
    public static final String MASTER_LOOK_CANON = "master_look_canon";
    public static final String MASTER_LOOK_DARKTABLE = "master_look_darktable";
    public static final String MASTER_LOOK_BASIC = "master_look_basic";
    // Look parameters (MasterLooks.paramsFor): 1/100 of the displayed unit (EV, exponent, factor) or percent.
    public static final String MASTER_BASIC_GAMMA = "master_basic_gamma";
    public static final String MASTER_AGX_BLACK = "master_agx_black";
    public static final String MASTER_AGX_WHITE = "master_agx_white";
    public static final String MASTER_AGX_TOE = "master_agx_toe";
    public static final String MASTER_AGX_SHOULDER = "master_agx_shoulder";
    public static final String MASTER_FILMIC_BLACK = "master_filmic_black";
    public static final String MASTER_FILMIC_WHITE = "master_filmic_white";
    public static final String MASTER_SIGMOID_CONTRAST = "master_sigmoid_contrast";
    public static final String MASTER_SIGMOID_HUE = "master_sigmoid_hue";
    /** Master colour mode (MasterLooks.MODE_*): 0 replace the stock LMT, 1 blend with it. */
    public static final String MASTER_LOOK_MODE = "master_look_mode";
    /** Blend mode: how much of the chosen look replaces the stock tone / colour, percent (100 = all of it). */
    public static final String MASTER_BLEND_TONE = "master_blend_tone";
    public static final String MASTER_BLEND_COLOUR = "master_blend_colour";
    /** Master colour exposure compensation in 1/100 EV on top of the engine default (no UI yet). */
    public static final String MASTER_LOOK_EXPOSURE = "master_look_exposure";
    /** Master colour exposure alignment to the stock look (MasterAlign.*): added to the exposure compensation. */
    public static final String MASTER_ALIGN = "master_align";

    /** Normal video: keep normal filters at 4K30 / 4K60 / 1080p60 (stock falls back to 1080p30). */
    public static final String VIDEO_FILTER_HIGH_SPEC = "video_filter_high_spec";

    /** Natural photo mode: master switch, the two features it turns off, and the extra softening (0-100 %). */
    public static final String NATURAL_MODE = "natural_mode";
    public static final String NATURAL_NO_AI_DETAIL = "natural_no_ai_detail";
    public static final String NATURAL_NO_LCE = "natural_no_lce";
    public static final String NATURAL_SOFTEN = "natural_soften";

    /** Normal-mode 25MP: relaxed lux / ISO / thermal gates and decision tree (applies after the camera restarts). */
    public static final String UNLOCK_25MP = "unlock_25mp";

    /** 哈苏超清: filters and palette (menu entries and the palette capture / preview path). */
    public static final String HIGH_PIXEL_EFFECTS = "high_pixel_effects";

    /** 哈苏超清: real 200MP readout without the big-face condition (applies after the camera restarts). */
    public static final String HIGH_PIXEL_200M_NO_FACE = "high_pixel_200m_no_face";

    /** Photo, portrait and 哈苏超清 (with its palette) list the master filters in the master order (applies after the camera restarts). */
    public static final String NORMAL_FOLLOWS_MASTER = "normal_follows_master";

    /** Experimental: replace a stock soft light level with a physically simulated filter (applies when the camera opens). */
    public static final String SOFT_LIGHT_WEAK_HDF = "soft_light_weak_hdf";
    public static final String SOFT_LIGHT_MEDIUM_BLACK_MIST = "soft_light_medium_black_mist";
    public static final String SOFT_LIGHT_STRONG_WHITE_MIST = "soft_light_strong_white_mist";

    /** Experimental: 和光 capture renders Portra 400's emulsion scatter, halation and DIR edges (实验/Portra光学) instead of the stock highlight blend. */
    public static final String MASTER_PORTRA_PHYSICAL = "master_portra_physical";

    /** Experimental: XPAN photos become live photos playing the developing animation (applies after the camera restarts). */
    public static final String XPAN_DEVELOPING_LIVE = "xpan_developing_live";

    /** Experimental: XPan 取景净化, each part on its own (applies when the camera opens). */
    public static final String XPAN_CLEAN_SMALL_MASK = "xpan_clean_small_mask";
    public static final String XPAN_CLEAN_LARGE_MASK = "xpan_clean_large_mask";
    public static final String XPAN_CLEAN_ANIMATION = "xpan_clean_animation";

    /** Watermark: the XPAN watermark applies only in XPAN mode (saved aside and cleared in other modes). */
    public static final String XPAN_WATERMARK_ISOLATION = "xpan_watermark_isolation";

    /** Model disguise: watermark text, and the EXIF Model (only while the watermark disguise is on). */
    public static final String MODEL_SPOOF_WATERMARK = "model_spoof_watermark";
    public static final String MODEL_SPOOF_EXIF = "model_spoof_exif";
    /** String settings: the selected model preset id, and the user's presets (ModelPresets JSON). */
    public static final String MODEL_PRESET_ID = "model_preset_id";
    public static final String MODEL_CUSTOM_PRESETS = "model_custom_presets";

    /** Quick launch key: open the camera in KEY_LAUNCH_MODE (a LaunchModes name) instead of the remembered mode. */
    public static final String KEY_LAUNCH_MODE_ON = "key_launch_mode_on";
    public static final String KEY_LAUNCH_MODE = "key_launch_mode";

    private ModuleSettings() {}

    public static String defaultString(String key) {
        if (MODEL_PRESET_ID.equals(key)) return ModelPresets.DEFAULT_ID;
        if (MODEL_CUSTOM_PRESETS.equals(key)) return "[]";
        if (KEY_LAUNCH_MODE.equals(key)) return LaunchModes.DEFAULT;
        throw new IllegalArgumentException("Unknown string setting " + key);
    }

    public static int defaultInt(String key) {
        if (SHUTTER_VOLUME.equals(key)) return 60;
        if (NATURAL_SOFTEN.equals(key)) return 0;
        if (MASTER_FILM_DENSITY_C.equals(key) || MASTER_FILM_DENSITY_M.equals(key) || MASTER_FILM_DENSITY_Y.equals(key)) return 100;
        if (MASTER_LOOK_PRINT.equals(key) || MASTER_HNCS_INTENT.equals(key) || MASTER_HNCS_CURVE.equals(key)) return 0;
        if (MASTER_LOOK.equals(key)) return MasterLooks.FAMILY_HNCS;
        Integer parameter = MasterLooks.paramDefault(key);
        if (parameter != null) return parameter;
        if (MASTER_LOOK_MODE.equals(key)) return MasterLooks.MODE_REPLACE;
        if (MASTER_BLEND_TONE.equals(key) || MASTER_BLEND_COLOUR.equals(key)) return 100;
        if (MASTER_LOOK_OPPO.equals(key) || MASTER_LOOK_LUMIX.equals(key) || MASTER_LOOK_FILM.equals(key) || MASTER_LOOK_EXPOSURE.equals(key) || MASTER_ALIGN.equals(key)
                || MASTER_LOOK_CANON.equals(key) || MASTER_LOOK_DARKTABLE.equals(key)
                || MASTER_LOOK_BASIC.equals(key)) {
            return 0;
        }
        throw new IllegalArgumentException("Unknown integer setting " + key);
    }

    public static boolean defaultValue(String key) {
        switch (key) {
            case SHUTTER_ALWAYS:
            case SHUTTER_VOLUME_CUSTOM:
            case VIDEO_FILTER_HIGH_SPEC:
            case POP_SDR_STYLE:
            case NATURAL_MODE:
            case NORMAL_FOLLOWS_MASTER:
            case XPAN_WATERMARK_ISOLATION:
            case MODEL_SPOOF_WATERMARK:
            case MODEL_SPOOF_EXIF:
            case KEY_LAUNCH_MODE_ON:
            case SOFT_LIGHT_WEAK_HDF:
            case SOFT_LIGHT_MEDIUM_BLACK_MIST:
            case SOFT_LIGHT_STRONG_WHITE_MIST:
            case XPAN_DEVELOPING_LIVE:
            case MASTER_PORTRA_PHYSICAL:
            case XPAN_CLEAN_SMALL_MASK:
            case XPAN_CLEAN_LARGE_MASK:
            case XPAN_CLEAN_ANIMATION:
            case HIGH_PIXEL_200M_NO_FACE:
                return false;
            case POP_HIGH_PIXEL:
            case UNLOCK_25MP:
            case HIGH_PIXEL_EFFECTS:
            case SHUTTER_IN_SILENT:
            case SHUTTER_IN_VIBRATE:
            case MASTER_EFFECT_NEON:
            case MASTER_EFFECT_PORTRA:
            case FLASH_PER_MODE:
            case LIVE_PHOTO_PER_MODE:
            case NATURAL_NO_AI_DETAIL:
            case NATURAL_NO_LCE:
                return true;
            default:
                throw new IllegalArgumentException("Unknown setting " + key);
        }
    }
}
