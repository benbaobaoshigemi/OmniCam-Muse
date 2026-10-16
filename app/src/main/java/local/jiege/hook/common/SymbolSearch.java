package local.jiege.hook.common;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

/**
 * Finds the symbols of assets/symbols.json in an APK with DexKit. Works on dex data only (no class
 * is loaded or initialised), so the same code runs in the hooked app and in the offline replay
 * (tools/symbols). Results are entries (see {@link #CLASS}, {@link #METHOD}, {@link #FIELD}),
 * turned into reflection objects by Symbols.
 *
 * Rules: every constraint of a symbol must hold; a symbol resolves if exactly one target matches
 * (at least one for "multi" symbols). A symbol referring to a missing symbol is missing too.
 */
public final class SymbolSearch {
    /** "c", class name. */
    public static final String CLASS = "c";
    /** "m", declaring class, name, return type, parameter types... (Java type names). */
    public static final String METHOD = "m";
    /** "f", declaring class, name, type. */
    public static final String FIELD = "f";

    public final Map<String, List<String>> resolved = new LinkedHashMap<>();
    public final Map<String, String> failures = new LinkedHashMap<>();
    private final DexKitBridge bridge;

    private SymbolSearch(DexKitBridge bridge) {
        this.bridge = bridge;
    }

    public static SymbolSearch run(DexKitBridge bridge, JSONObject spec) throws Exception {
        SymbolSearch search = new SymbolSearch(bridge);
        List<String> pending = new ArrayList<>();
        for (Iterator<String> it = spec.keys(); it.hasNext(); ) pending.add(it.next());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String id = it.next();
                JSONObject symbol = spec.getJSONObject(id);
                if (!search.dependenciesSettled(symbol, spec)) continue;
                it.remove();
                progress = true;
                List<String> missing = search.missingDependencies(symbol);
                if (!missing.isEmpty()) {
                    search.failures.put(id, "needs " + missing);
                    continue;
                }
                try {
                    List<String> hits = search.find(symbol);
                    boolean multi = symbol.optBoolean("multi");
                    if (hits.size() == 1 || (multi && !hits.isEmpty())) search.resolved.put(id, hits);
                    else search.failures.put(id, hits.size() + " matches " + (hits.size() > 4 ? hits.subList(0, 4) : hits));
                } catch (Throwable error) {
                    search.failures.put(id, String.valueOf(error));
                }
            }
        }
        for (String id : pending) search.failures.put(id, "circular reference");
        return search;
    }

    // --- dependencies -------------------------------------------------------------------------

    private boolean dependenciesSettled(JSONObject symbol, JSONObject spec) {
        for (String ref : references(symbol)) {
            if (spec.has(ref) && !resolved.containsKey(ref) && !failures.containsKey(ref)) return false;
        }
        return true;
    }

    private List<String> missingDependencies(JSONObject symbol) {
        List<String> missing = new ArrayList<>();
        for (String ref : references(symbol)) if (!resolved.containsKey(ref)) missing.add(ref);
        return missing;
    }

    private static List<String> references(JSONObject symbol) {
        List<String> refs = new ArrayList<>();
        for (Iterator<String> it = symbol.keys(); it.hasNext(); ) collect(symbol.opt(it.next()), refs);
        return refs;
    }

    private static void collect(Object value, List<String> refs) {
        if (value instanceof String && ((String) value).startsWith("@")) {
            refs.add(((String) value).substring(1));
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), refs);
        }
    }

    // --- matching -----------------------------------------------------------------------------

    private List<String> find(JSONObject s) throws Exception {
        switch (s.getString("kind")) {
            case "class": return findClass(s);
            case "method": return findMethod(s);
            case "field": return findField(s);
            default: throw new IllegalArgumentException(s.getString("kind"));
        }
    }

    private List<String> findClass(JSONObject s) throws Exception {
        if (s.has("name")) {
            ClassData data = bridge.getClassData(s.getString("name"));
            return data == null ? new ArrayList<>() : one(classEntry(data.getName()));
        }
        if (s.has("superOf")) {
            ClassData data = bridge.getClassData(typeName(s.getString("superOf")));
            ClassData parent = data == null ? null : data.getSuperClass();
            return parent == null ? new ArrayList<>() : one(classEntry(parent.getName()));
        }
        if (s.has("interfaceOf")) {
            List<String> out = new ArrayList<>();
            ClassData data = bridge.getClassData(typeName(s.getString("interfaceOf")));
            if (data != null) for (ClassData iface : data.getInterfaces()) out.add(classEntry(iface.getName()));
            return out;
        }
        if (s.has("typeOf")) return one(classEntry(entry(s.getString("typeOf"))[3]));
        if (s.has("returnOf")) return one(classEntry(entry(s.getString("returnOf"))[3]));
        if (s.has("ownerOf")) return one(classEntry(entry(s.getString("ownerOf"))[1]));
        if (s.has("paramOf")) {
            JSONArray p = s.getJSONArray("paramOf");
            return one(classEntry(entry(p.getString(0))[4 + p.getInt(1)]));
        }
        ClassMatcher matcher = ClassMatcher.create();
        List<String> strings = strings(s, "strings");
        if (!strings.isEmpty()) matcher.usingEqStrings(strings);
        if (s.has("super")) matcher.superClass(typeName(s.getString("super")));
        for (String iface : strings(s, "ifaces")) matcher.addInterface(typeName(iface));
        for (String type : strings(s, "fieldTypes")) matcher.addFieldForType(typeName(type));
        List<String> staticTypes = new ArrayList<>();
        for (String type : strings(s, "staticFieldTypes")) staticTypes.add(typeName(type));
        for (String type : staticTypes) matcher.addFieldForType(type);
        List<String> clinit = strings(s, "clinitStrings");
        if (!clinit.isEmpty()) matcher.addMethod(MethodMatcher.create().name("<clinit>").usingEqStrings(clinit));
        List<String> hits = new ArrayList<>();
        for (ClassData data : bridge.findClass(FindClass.create().matcher(matcher))) {
            if (s.has("super")) {
                ClassData parent = data.getSuperClass();
                if (parent == null || !parent.getName().equals(typeName(s.getString("super")))) continue;
            }
            if (!hasStaticFields(data, staticTypes)) continue;
            hits.add(classEntry(data.getName()));
        }
        return hits;
    }

    private static boolean hasStaticFields(ClassData data, List<String> typeNames) {
        for (String name : typeNames) {
            boolean found = false;
            for (FieldData field : data.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getTypeName().equals(name)) found = true;
            }
            if (!found) return false;
        }
        return true;
    }

    private List<String> findMethod(JSONObject s) throws Exception {
        if (s.has("overrideOf")) {
            String[] base = entry(s.getString("overrideOf"));
            ClassData owner = bridge.getClassData(typeName(s.getString("in")));
            List<String> hits = new ArrayList<>();
            if (owner == null) return hits;
            for (MethodData method : owner.getMethods()) {
                if (method.isMethod() && sameSignature(method, base)) hits.add(methodEntry(method));
            }
            return hits;
        }
        if (s.has("implOf")) {
            String[] base = entry(s.getString("implOf"));
            MethodMatcher matcher = MethodMatcher.create().name(base[2]).returnType(base[3]).paramTypes(params(base));
            List<String> hits = new ArrayList<>();
            for (MethodData method : bridge.findMethod(FindMethod.create().matcher(matcher))) {
                if (!method.isMethod() || Modifier.isAbstract(method.getModifiers()) || !sameSignature(method, base)) continue;
                String owner = method.getClassName();
                if (!owner.equals(base[1]) && assignable(owner, base[1])) hits.add(methodEntry(method));
            }
            return hits;
        }
        MethodMatcher matcher = MethodMatcher.create();
        if (s.has("in")) matcher.declaredClass(typeName(s.getString("in")));
        if (s.has("name")) matcher.name(s.getString("name"));
        JSONArray params = s.optJSONArray("params");
        if (params != null) matcher.paramCount(params.length());
        if (s.has("ret")) matcher.returnType(typeName(s.getString("ret")));
        List<String> strings = strings(s, "strings");
        if (!strings.isEmpty()) matcher.usingEqStrings(strings);
        JSONArray numbers = s.optJSONArray("numbers");
        if (numbers != null) {
            List<Number> values = new ArrayList<>();
            for (int i = 0; i < numbers.length(); i++) values.add(numbers.getInt(i));
            matcher.usingNumbers(values);
        }
        for (String invoke : strings(s, "invokes")) matcher.addInvoke(methodDescriptor(invoke));
        for (String field : strings(s, "fields")) matcher.addUsingField(fieldDescriptor(field));
        for (String caller : strings(s, "calledBy")) matcher.addCaller(methodDescriptor(caller));
        List<String> notInvokes = new ArrayList<>();
        for (String invoke : strings(s, "notInvokes")) notInvokes.add(methodDescriptor(invoke));

        List<String> hits = new ArrayList<>();
        for (MethodData method : bridge.findMethod(FindMethod.create().matcher(matcher))) {
            if (!method.isMethod()) continue;
            if (params != null && !paramsMatch(params, method.getParamTypeNames())) continue;
            int modifiers = method.getModifiers();
            if (s.has("static") && Modifier.isStatic(modifiers) != s.getBoolean("static")) continue;
            if (s.has("abstract") && Modifier.isAbstract(modifiers) != s.getBoolean("abstract")) continue;
            if (!notInvokes.isEmpty()) {
                boolean excluded = false;
                for (MethodData invoked : method.getInvokes()) if (notInvokes.contains(invoked.getDescriptor())) excluded = true;
                if (excluded) continue;
            }
            hits.add(methodEntry(method));
        }
        return hits;
    }

    private List<String> findField(JSONObject s) throws Exception {
        FieldMatcher matcher = FieldMatcher.create();
        if (s.has("in")) matcher.declaredClass(typeName(s.getString("in")));
        if (s.has("name")) matcher.name(s.getString("name"));
        if (s.has("type")) matcher.type(typeName(s.getString("type")));
        for (String reader : strings(s, "readBy")) matcher.addReadMethod(methodDescriptor(reader));
        for (String writer : strings(s, "writtenBy")) matcher.addWriteMethod(methodDescriptor(writer));
        List<String> notWriters = new ArrayList<>();
        for (String writer : strings(s, "notWrittenBy")) notWriters.add(methodDescriptor(writer));
        List<String> hits = new ArrayList<>();
        for (FieldData field : bridge.findField(FindField.create().matcher(matcher))) {
            if (s.has("static") && Modifier.isStatic(field.getModifiers()) != s.getBoolean("static")) continue;
            boolean excluded = false;
            for (MethodData writer : field.getWriters()) if (notWriters.contains(writer.getDescriptor())) excluded = true;
            if (excluded) continue;
            hits.add(join(FIELD, field.getClassName(), field.getFieldName(), field.getTypeName()));
        }
        return hits;
    }

    // --- dex relations ------------------------------------------------------------------------

    private boolean assignable(String type, String target) {
        List<String> todo = new ArrayList<>();
        todo.add(type);
        List<String> seen = new ArrayList<>();
        while (!todo.isEmpty()) {
            String name = todo.remove(todo.size() - 1);
            if (name.equals(target)) return true;
            if (seen.contains(name)) continue;
            seen.add(name);
            ClassData data = bridge.getClassData(name);
            if (data == null) continue;
            ClassData parent = data.getSuperClass();
            if (parent != null) todo.add(parent.getName());
            for (ClassData iface : data.getInterfaces()) todo.add(iface.getName());
        }
        return false;
    }

    private static boolean sameSignature(MethodData method, String[] base) {
        return method.getMethodName().equals(base[2]) && method.getReturnTypeName().equals(base[3])
            && method.getParamTypeNames().equals(params(base));
    }

    // --- entries and spec values --------------------------------------------------------------

    private static String classEntry(String name) {
        return join(CLASS, name);
    }

    private static String methodEntry(MethodData method) {
        List<String> parts = new ArrayList<>();
        parts.add(METHOD);
        parts.add(method.getClassName());
        parts.add(method.getMethodName());
        parts.add(method.getReturnTypeName());
        parts.addAll(method.getParamTypeNames());
        return join(parts.toArray(new String[0]));
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append('\t');
            out.append(part);
        }
        return out.toString();
    }

    public static String[] split(String entry) {
        return entry.split("\t", -1);
    }

    private static List<String> params(String[] method) {
        List<String> out = new ArrayList<>();
        for (int i = 4; i < method.length; i++) out.add(method[i]);
        return out;
    }

    /** The resolved entry of "@Id", split. */
    private String[] entry(String ref) {
        return split(resolved.get(ref.substring(1)).get(0));
    }

    /** Java type name of a spec type: "int", "a.B", "a.B[]", or "@Id" (a class, or a member's declaring class). */
    private String typeName(String value) {
        if (!value.startsWith("@")) return value;
        String[] target = entry(value);
        return target[1];
    }

    private String methodDescriptor(String value) {
        if (!value.startsWith("@")) return value;
        String[] m = entry(value);
        StringBuilder out = new StringBuilder(descriptor(m[1])).append("->").append(m[2]).append('(');
        for (int i = 4; i < m.length; i++) out.append(descriptor(m[i]));
        return out.append(')').append(descriptor(m[3])).toString();
    }

    private String fieldDescriptor(String value) {
        if (!value.startsWith("@")) return value;
        String[] f = entry(value);
        return descriptor(f[1]) + "->" + f[2] + ":" + descriptor(f[3]);
    }

    /** Dex descriptor of a Java type name ("int", "a.B$C", "a.B[]"). */
    public static String descriptor(String type) {
        if (type.endsWith("[]")) return "[" + descriptor(type.substring(0, type.length() - 2));
        switch (type) {
            case "int": return "I";
            case "boolean": return "Z";
            case "float": return "F";
            case "long": return "J";
            case "double": return "D";
            case "void": return "V";
            case "byte": return "B";
            case "char": return "C";
            case "short": return "S";
            default: return "L" + type.replace('.', '/') + ";";
        }
    }

    private boolean paramsMatch(JSONArray wanted, List<String> actual) throws Exception {
        if (wanted.length() != actual.size()) return false;
        for (int i = 0; i < wanted.length(); i++) {
            if (wanted.isNull(i)) continue;
            if (!typeName(wanted.getString(i)).equals(actual.get(i))) return false;
        }
        return true;
    }

    private static List<String> strings(JSONObject s, String key) throws Exception {
        JSONArray array = s.optJSONArray(key);
        List<String> out = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        return out;
    }

    private static List<String> one(String value) {
        List<String> out = new ArrayList<>();
        out.add(value);
        return out;
    }
}
