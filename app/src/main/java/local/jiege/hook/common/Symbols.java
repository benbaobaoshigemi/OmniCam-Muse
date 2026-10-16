package local.jiege.hook.common;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;

/**
 * Obfuscated classes and members of the hooked apps, found by fingerprint instead of by name.
 *
 * The fingerprints are in assets/symbols.json (one section per app). Each symbol says what it
 * must be: strings its code uses, framework APIs it calls, its signature, and its relation to
 * other symbols (caller, override, declaring or field type). SymbolSearch finds them with DexKit;
 * the same search is replayed offline against known app builds. A symbol that does not match
 * exactly once stays missing, and only the features that need it stay off.
 *
 * Results are cached per app build (APK path, size, time, spec) in the app's cache directory, so
 * only the first start after an app or module update pays for the search.
 */
public final class Symbols {
    private static final String TAG = "Symbols";
    private static final String SPEC = "assets/symbols.json";
    private static final int CACHE_VERSION = 2;

    private static volatile Symbols current;
    private static volatile String modulePath;

    private final ClassLoader loader;
    private final Map<String, List<Object>> resolved = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();

    private Symbols(ClassLoader loader) {
        this.loader = loader;
    }

    /** The module APK (from zygote init), used to read the spec if the class loader has no resources. */
    public static void setModulePath(String path) {
        modulePath = path;
    }

    /** The module APK path given at zygote init. */
    public static String modulePath() {
        return modulePath;
    }

    /** Symbols of the app in this process; install() must have run. */
    public static Symbols get() {
        Symbols symbols = current;
        if (symbols == null) throw new IllegalStateException("symbols not installed");
        return symbols;
    }

    /** Resolves the symbols of one app section; never throws (missing symbols are logged). */
    public static Symbols install(String section, ClassLoader loader, String apkPath, String dataDir) {
        Symbols symbols = new Symbols(loader);
        current = symbols;
        long start = System.currentTimeMillis();
        String how = "error";
        try {
            JSONObject spec = new JSONObject(readSpec()).getJSONObject(section);
            File apk = new File(apkPath);
            String key = CACHE_VERSION + "|" + sha1(spec.toString()) + "|" + apk.getAbsolutePath() + "|" + apk.length() + "|" + apk.lastModified();
            File cache = new File(dataDir, "cache/jiege_symbols_" + section + ".json");
            JSONObject entries = readCache(cache, key);
            how = "cache";
            if (entries == null || !symbols.decodeAll(entries, spec)) {
                entries = search(spec, apkPath);
                how = "search";
                symbols.resolved.clear();
                symbols.failures.clear();
                symbols.decodeAll(entries, spec);
                writeCache(cache, key, entries);
            }
        } catch (Throwable error) {
            Log.e(TAG, "symbol resolution failed", error);
        }
        Log.i(TAG, section + ": " + symbols.resolved.size() + " resolved, " + symbols.failures.size() + " missing ("
            + how + ", " + (System.currentTimeMillis() - start) + " ms)");
        for (Map.Entry<String, String> failure : symbols.failures.entrySet()) {
            Log.i(TAG, "  missing " + failure.getKey() + ": " + failure.getValue());
        }
        Diagnostics.begin(section, loader, apkPath, symbols.resolved.size(), symbols.failures.size());
        return symbols;
    }

    // --- lookups used by the hooks ------------------------------------------------------------

    public boolean has(String... ids) {
        for (String id : ids) if (!resolved.containsKey(id)) return false;
        return true;
    }

    public Class<?> cls(String id) {
        return (Class<?>) one(id);
    }

    public Method method(String id) {
        return (Method) one(id);
    }

    public Field field(String id) {
        return (Field) one(id);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> all(String id) {
        List<Object> values = resolved.get(id);
        if (values == null) throw new MissingSymbol(id, failures.get(id));
        return (List<T>) Collections.unmodifiableList(values);
    }

    /** Member name of a resolved method or field symbol. */
    public String name(String id) {
        Object value = one(id);
        return value instanceof Method ? ((Method) value).getName() : ((Field) value).getName();
    }

    public Object get(String fieldId, Object target) throws IllegalAccessException {
        return field(fieldId).get(target);
    }

    public void set(String fieldId, Object target, Object value) throws IllegalAccessException {
        field(fieldId).set(target, value);
    }

    /** Invokes a method symbol (virtually, so subclasses' overrides run); rethrows the method's own exception. */
    public Object call(String methodId, Object target, Object... args) throws Throwable {
        try {
            return method(methodId).invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private Object one(String id) {
        List<Object> values = resolved.get(id);
        if (values == null) throw new MissingSymbol(id, failures.get(id));
        return values.get(0);
    }

    /** A feature asked for a symbol that did not resolve on this app build. */
    public static final class MissingSymbol extends RuntimeException {
        MissingSymbol(String id, String why) {
            super("symbol " + id + " unavailable" + (why == null ? "" : " (" + why + ")"));
        }
    }

    // --- search and decoding ------------------------------------------------------------------

    /** {"resolved": {id: [entry...]}, "failures": {id: reason}} */
    private static JSONObject search(JSONObject spec, String apkPath) throws Exception {
        System.loadLibrary("dexkit");
        try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
            SymbolSearch search = SymbolSearch.run(bridge, spec);
            JSONObject resolved = new JSONObject();
            for (Map.Entry<String, List<String>> entry : search.resolved.entrySet()) resolved.put(entry.getKey(), new JSONArray(entry.getValue()));
            return new JSONObject().put("resolved", resolved).put("failures", new JSONObject(search.failures));
        }
    }

    /** Turns entries into reflection objects; false if the entries do not fit (stale cache). */
    private boolean decodeAll(JSONObject entries, JSONObject spec) {
        try {
            JSONObject found = entries.getJSONObject("resolved");
            JSONObject missing = entries.getJSONObject("failures");
            for (Iterator<String> it = spec.keys(); it.hasNext(); ) {
                String id = it.next();
                if (found.has(id)) {
                    JSONArray values = found.getJSONArray(id);
                    List<Object> decoded = new ArrayList<>();
                    for (int i = 0; i < values.length(); i++) decoded.add(decode(values.getString(i)));
                    resolved.put(id, decoded);
                } else if (missing.has(id)) {
                    failures.put(id, missing.getString(id));
                } else {
                    return false;
                }
            }
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "symbol entries unusable", error);
            resolved.clear();
            failures.clear();
            return false;
        }
    }

    private Object decode(String entry) throws Exception {
        String[] parts = SymbolSearch.split(entry);
        switch (parts[0]) {
            case SymbolSearch.CLASS:
                return type(parts[1]);
            case SymbolSearch.METHOD: {
                Class<?>[] types = new Class<?>[parts.length - 4];
                for (int i = 0; i < types.length; i++) types[i] = type(parts[i + 4]);
                Method method = type(parts[1]).getDeclaredMethod(parts[2], types);
                method.setAccessible(true);
                return method;
            }
            case SymbolSearch.FIELD: {
                Field field = type(parts[1]).getDeclaredField(parts[2]);
                field.setAccessible(true);
                return field;
            }
            default:
                throw new IllegalArgumentException(entry);
        }
    }

    /** Class for a Java type name ("int", "a.B$C", "a.B[]"), loaded without initialising it. */
    private Class<?> type(String name) throws ClassNotFoundException {
        switch (name) {
            case "int": return int.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "long": return long.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "void": return void.class;
            default:
                if (name.endsWith("[]")) {
                    return Class.forName(SymbolSearch.descriptor(name).replace('/', '.'), false, loader);
                }
                return Class.forName(name, false, loader);
        }
    }

    // --- cache and io -------------------------------------------------------------------------

    private static JSONObject readCache(File file, String key) {
        if (!file.isFile()) return null;
        try {
            JSONObject root = new JSONObject(new String(readAll(new FileInputStream(file)), "UTF-8"));
            return key.equals(root.optString("key")) ? root.getJSONObject("entries") : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static void writeCache(File file, String key, JSONObject entries) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            File temp = new File(file.getPath() + "." + android.os.Process.myPid() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(new JSONObject().put("key", key).put("entries", entries).toString().getBytes("UTF-8"));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Throwable error) {
            Log.e(TAG, "symbol cache not written", error);
        }
    }

    private static String readSpec() throws Exception {
        InputStream in = Symbols.class.getClassLoader().getResourceAsStream(SPEC);
        if (in != null) return new String(readAll(in), "UTF-8");
        String path = modulePath;
        if (path == null) throw new IllegalStateException(SPEC + " not readable (no module path)");
        try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(path)) {
            java.util.zip.ZipEntry entry = apk.getEntry(SPEC);
            if (entry == null) throw new IllegalStateException(SPEC + " missing from " + path);
            return new String(readAll(apk.getInputStream(entry)), "UTF-8");
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String sha1(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8"));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b));
        return out.toString();
    }
}
