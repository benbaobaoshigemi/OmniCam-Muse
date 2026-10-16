import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import local.jiege.hook.common.SymbolSearch;
import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;

/**
 * Offline replay of the module's own SymbolSearch (the code the hooks run) on an app APK, with a
 * host build of DexKit. Usage: ReplayMain <symbols.json> <section> <apk> <out.json>
 */
public final class ReplayMain {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("dexkit");
        JSONObject spec = new JSONObject(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8)).getJSONObject(args[1]);
        long start = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        try (DexKitBridge bridge = DexKitBridge.create(args[2])) {
            SymbolSearch search = SymbolSearch.run(bridge, spec);
            JSONObject resolved = new JSONObject();
            for (Map.Entry<String, List<String>> entry : search.resolved.entrySet()) resolved.put(entry.getKey(), new JSONArray(entry.getValue()));
            out.put("resolved", resolved).put("failures", new JSONObject(search.failures));
        }
        out.put("millis", System.currentTimeMillis() - start);
        Files.write(Paths.get(args[3]), out.toString(1).getBytes(StandardCharsets.UTF_8));
        System.out.println(args[1] + " " + args[2] + ": " + out.getJSONObject("resolved").length() + " resolved, "
            + out.getJSONObject("failures").length() + " missing, " + out.getLong("millis") + " ms");
    }
}
