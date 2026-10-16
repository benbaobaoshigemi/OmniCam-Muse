package local.jiege.settings;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Device model presets for the model disguise (watermark text and EXIF Model). Two built-in
 * presets, plus user presets kept as a JSON array [{"id","name"}] in ModuleSettings.MODEL_CUSTOM_PRESETS.
 * Shared by the settings UI and the hooks, so it only uses org.json.
 */
public final class ModelPresets {
    public static final String DEFAULT_ID = "oppo_find_x10_ultra";
    private static final String CUSTOM_PREFIX = "custom_";

    public static final class Preset {
        public final String id;
        public final String name;
        public final boolean builtin;

        Preset(String id, String name, boolean builtin) {
            this.id = id;
            this.name = name;
            this.builtin = builtin;
        }
    }

    private static final Preset[] BUILTIN = {
        new Preset(DEFAULT_ID, "OPPO Find X10 Ultra", true),
        new Preset("hasselblad_x2d_100c", "Hasselblad X2D 100C", true),
    };

    private ModelPresets() {}

    /** Built-in presets first, then the user's in the order they were added. */
    public static List<Preset> all(String customJson) {
        List<Preset> list = new ArrayList<>();
        for (Preset preset : BUILTIN) list.add(preset);
        try {
            JSONArray array = new JSONArray(customJson == null || customJson.isEmpty() ? "[]" : customJson);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.optString("id");
                String name = item.optString("name");
                if (!id.isEmpty() && !name.isEmpty()) list.add(new Preset(id, name, false));
            }
        } catch (JSONException ignored) {
            // A damaged list leaves only the built-in presets.
        }
        return list;
    }

    /** The model name of the selected preset; an unknown id falls back to the default preset. */
    public static String nameFor(String selectedId, String customJson) {
        List<Preset> list = all(customJson);
        for (Preset preset : list) {
            if (preset.id.equals(selectedId)) return preset.name;
        }
        return list.get(0).name;
    }

    /** The list with a new user preset appended. */
    public static String add(String customJson, String name) {
        JSONArray array = parse(customJson);
        try {
            array.put(new JSONObject().put("id", CUSTOM_PREFIX + System.currentTimeMillis()).put("name", name));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return array.toString();
    }

    /** The list without the user preset {@code id}; built-in ids are not in it, so they never match. */
    public static String remove(String customJson, String id) {
        JSONArray array = parse(customJson);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && !id.equals(item.optString("id"))) kept.put(item);
        }
        return kept.toString();
    }

    private static JSONArray parse(String customJson) {
        try {
            return new JSONArray(customJson == null || customJson.isEmpty() ? "[]" : customJson);
        } catch (JSONException damaged) {
            return new JSONArray();
        }
    }
}
