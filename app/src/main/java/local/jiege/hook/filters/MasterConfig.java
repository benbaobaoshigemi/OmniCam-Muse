package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The user's master filter configuration, as the settings app saves it and the camera reads it:
 * the list order, visibility overrides and the imported filters. Plain Java (no Android JSON) so
 * it can be tested offline.
 *
 * Text format, one record per line, fields separated by tabs (tabs and line breaks are removed
 * from titles):
 *   jiege-master 1
 *   order    <filter id>
 *   visible  <filter id>  <0|1>
 *   import   <filter id>  <title>  <LUT file size>
 * Unknown record kinds are ignored, so later versions can add records.
 */
public final class MasterConfig {
    private static final String MAGIC = "jiege-master 1";
    /** ID prefix of imported filters; never used by built-in ones. */
    public static final String IMPORT_PREFIX = "JG_IMPORT_";
    public static final String LUT_SUFFIX = ".cube.rgb.bin";

    public static final class Imported {
        public final String id;
        public final String title;
        public final long size;

        public Imported(String id, String title, long size) {
            if (!isImportId(id)) throw new IllegalArgumentException("Not an import ID: " + id);
            this.id = id;
            this.title = clean(title);
            this.size = size;
        }
    }

    public final List<String> order;
    public final Map<String, Boolean> visible;
    public final List<Imported> imported;

    public MasterConfig(List<String> order, Map<String, Boolean> visible, List<Imported> imported) {
        this.order = Collections.unmodifiableList(new ArrayList<>(order));
        this.visible = Collections.unmodifiableMap(new LinkedHashMap<>(visible));
        this.imported = Collections.unmodifiableList(new ArrayList<>(imported));
    }

    public static MasterConfig empty() {
        return new MasterConfig(Collections.<String>emptyList(), Collections.<String, Boolean>emptyMap(),
            Collections.<Imported>emptyList());
    }

    public MasterLayout.Saved layout() {
        return new MasterLayout.Saved(order, visible);
    }

    public static boolean isImportId(String id) {
        return id != null && id.startsWith(IMPORT_PREFIX) && id.endsWith(LUT_SUFFIX)
            && id.indexOf('/') < 0 && id.indexOf('\\') < 0 && !id.contains("..")
            && id.length() > IMPORT_PREFIX.length() + LUT_SUFFIX.length();
    }

    public String write() {
        StringBuilder out = new StringBuilder(MAGIC).append('\n');
        for (String id : order) out.append("order\t").append(clean(id)).append('\n');
        for (Map.Entry<String, Boolean> entry : visible.entrySet()) {
            out.append("visible\t").append(clean(entry.getKey())).append('\t').append(entry.getValue() ? '1' : '0').append('\n');
        }
        for (Imported item : imported) {
            out.append("import\t").append(item.id).append('\t').append(item.title).append('\t').append(item.size).append('\n');
        }
        return out.toString();
    }

    /** Parses a saved configuration; malformed records are skipped, a wrong header gives empty(). */
    public static MasterConfig parse(String text) {
        if (text == null) return empty();
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) return empty();
        List<String> order = new ArrayList<>();
        Map<String, Boolean> visible = new LinkedHashMap<>();
        List<Imported> imported = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].replace("\r", "").split("\t", -1);
            try {
                switch (fields[0]) {
                    case "order":
                        if (fields.length >= 2 && !fields[1].isEmpty() && !order.contains(fields[1])) order.add(fields[1]);
                        break;
                    case "visible":
                        if (fields.length >= 3 && !fields[1].isEmpty()) visible.put(fields[1], "1".equals(fields[2]));
                        break;
                    case "import":
                        if (fields.length >= 4) imported.add(new Imported(fields[1], fields[2], Long.parseLong(fields[3])));
                        break;
                    default:
                        break;
                }
            } catch (IllegalArgumentException malformed) {
                // skip the record
            }
        }
        return new MasterConfig(order, visible, imported);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }
}
