package local.jiege.hook.filters;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pro-gear parameters saved with a master preset (MasterPreset.ProParams), stored as one text
 * value per preset: "<camera setting key>\t<value>" per line. Plain Java for offline tests.
 * The keys are the camera's own preference keys, so the record also keeps parameters this module
 * does not know by name.
 */
public final class ProParamsRecord {
    public static final String ISO = "pref_professional_iso_key";
    public static final String EXPOSURE_TIME = "pref_professional_exposure_time_key";
    public static final String WHITE_BALANCE = "pref_professional_whitebalance_key";
    public static final String EXPOSURE_COMPENSATION = "pref_professional_exposure_compensation_key";
    public static final String FOCUS_MODE = "pref_professional_focus_mode_key";
    public static final String COLOR_TONE = "pref_professional_color_tone_key";

    private ProParamsRecord() {}

    public static String write(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            out.append(clean(entry.getKey())).append('\t').append(clean(entry.getValue())).append('\n');
        }
        return out.toString();
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) return values;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            values.put(line.substring(0, tab), line.substring(tab + 1).replace("\r", ""));
        }
        return values;
    }

    /** The typed view for MasterPreset. */
    public static MasterPreset.ProParams toProParams(Map<String, String> values) {
        Map<String, String> extra = new LinkedHashMap<>(values);
        return new MasterPreset.ProParams(extra.remove(ISO), extra.remove(EXPOSURE_TIME), extra.remove(WHITE_BALANCE),
            extra.remove(EXPOSURE_COMPENSATION), extra.remove(FOCUS_MODE), Collections.unmodifiableMap(extra));
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
