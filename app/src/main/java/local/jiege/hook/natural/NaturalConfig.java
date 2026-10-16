package local.jiege.hook.natural;

import java.util.Iterator;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Normal-photo "natural" edit of the stock conf_tuning_params.json: sets the strengths of the AI
 * detail enhancement and of the local contrast enhancement (LCE) to zero. The result is served to
 * the camera algorithms in place of the ODM file (native side: natural_mode.cpp).
 */
final class NaturalConfig {
    /** Sections holding the EnhanceNet (AI detail) blend weights. */
    private static final String[] ENHANCE_SECTIONS = {"EnhanceNetParamsSet", "EnhanceNetJDDGANParamsSet"};
    private static final Pattern ENHANCE_WEIGHT = Pattern.compile(".*(BlendWeight|MaskWeight)$");
    /** Section with the hair USM and the LCE strengths. */
    private static final String TMC_SECTION = "TMCParamsSet";
    private static final Pattern USM_STRENGTH = Pattern.compile("usmStrength(1X|3X)");
    private static final Pattern LCE_STRENGTH = Pattern.compile("lceStrength(Face)?");

    private NaturalConfig() {}

    /** Returns the stock json with the selected features turned off. */
    static String apply(String stockJson, boolean noAiDetail, boolean noLce) throws JSONException {
        JSONObject root = new JSONObject(stockJson);
        if (noAiDetail) {
            for (String section : ENHANCE_SECTIONS) {
                zeroMatching(root.optJSONObject(section), ENHANCE_WEIGHT);
            }
            zeroMatching(root.optJSONObject(TMC_SECTION), USM_STRENGTH);
        }
        if (noLce) {
            zeroMatching(root.optJSONObject(TMC_SECTION), LCE_STRENGTH);
        }
        return root.toString();
    }

    /** Zeroes every value, at any depth, whose key matches. */
    private static void zeroMatching(Object node, Pattern key) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = object.get(name);
                if (key.matcher(name).matches()) {
                    object.put(name, zeroed(value));
                } else {
                    zeroMatching(value, key);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                zeroMatching(array.get(i), key);
            }
        }
    }

    private static Object zeroed(Object value) throws JSONException {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                result.put(zeroed(array.get(i)));
            }
            return result;
        }
        return value instanceof Integer || value instanceof Long ? (Object) 0 : (Object) 0.0;
    }
}
