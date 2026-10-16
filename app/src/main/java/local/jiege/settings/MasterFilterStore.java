package local.jiege.settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.MasterConfig;
import local.jiege.hook.filters.MasterLayout;
import local.jiege.lut.Rec709LutBaker;

/**
 * The settings app's master filter data (plain Java, no Android, so it can be tested offline).
 * All files lie flat in one directory, readable by everyone:
 *   <dir>/master_config.txt   MasterConfig (order, visibility, imported filters)
 *   <dir>/master_camera.txt   the camera's laid-out master list (MasterFilterContract stock text)
 *   <dir>/master_lut_<id>     imported LUTs, device format
 * In the app the directory is the module's LSPosed preferences directory (xposedsharedprefs),
 * which the camera reads directly (MasterFilterSync), without starting this app. Changes apply
 * when the camera starts next.
 */
public final class MasterFilterStore {
    public static final String CONFIG = "master_config.txt";
    private static final String CAMERA = "master_camera.txt";
    public static final String LUT_PREFIX = "master_lut_";
    /** Backup archive layout: config.txt and lut/<id>. */
    private static final String BACKUP_CONFIG = "config.txt";
    private static final String BACKUP_LUT = "lut/";
    /** Names of X10 filters, which keep the camera's own name resource. */
    private static final Map<String, String> CAMERA_NAMES = new LinkedHashMap<>();

    static {
        CAMERA_NAMES.put(MasterLayout.STANDARD, "标准");
        CAMERA_NAMES.put("qing_tou.bin", "清透");
        CAMERA_NAMES.put("hu_po.bin", "琥珀");
    }

    /** One line of the master filter list in the settings app. */
    public static final class Row {
        public final String id;
        public final String category;
        public final String title;
        public final boolean visible;
        /** 标准 cannot be moved or hidden. */
        public final boolean fixed;
        public final boolean imported;

        Row(String id, String category, String title, boolean visible, boolean fixed, boolean imported) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.visible = visible;
            this.fixed = fixed;
            this.imported = imported;
        }

        /** "类别-名称". */
        public String displayName() {
            return category + "-" + title;
        }
    }

    private final File dir;

    public MasterFilterStore(File dir) {
        this.dir = dir;
    }

    // ---- configuration --------------------------------------------------------------------------

    public synchronized MasterConfig config() {
        return MasterConfig.parse(read(new File(dir, CONFIG)));
    }

    public synchronized String configText() {
        return config().write();
    }

    private void save(MasterConfig config) throws IOException {
        write(new File(dir, CONFIG), config.write().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void saveCameraList(String text) throws IOException {
        write(new File(dir, CAMERA), (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    /** True once the camera has reported its master list (camera started with the module). */
    public synchronized boolean hasCameraList() {
        return new File(dir, CAMERA).isFile();
    }

    public File lutFile(String id) {
        return lutFile(dir, id);
    }

    public static File lutFile(File dir, String id) {
        if (!MasterConfig.isImportId(id)) throw new IllegalArgumentException("Not an imported filter: " + id);
        return new File(dir, LUT_PREFIX + id);
    }

    // ---- list -------------------------------------------------------------------------------------

    /** The master list as the camera will show it next start: visible first, then hidden. */
    public synchronized List<Row> rows() {
        MasterConfig config = config();
        FilterCatalog.configure(config);
        FilterCatalog catalog = FilterCatalog.get(FilterScope.MASTER);
        Map<String, String> cameraNames = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        for (String line : read(new File(dir, CAMERA)).split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            String id = fields[0];
            // Imported filters deleted since the camera's report are gone.
            if (MasterConfig.isImportId(id) && catalog.find(id) == null) continue;
            if (!current.contains(id)) current.add(id);
            if (fields.length > 1 && !fields[1].isEmpty()) cameraNames.put(id, fields[1]);
        }
        if (!current.contains(MasterLayout.STANDARD)) current.add(0, MasterLayout.STANDARD);
        for (FilterEntry entry : catalog.entries()) if (!current.contains(entry.id)) current.add(entry.id);

        MasterLayout.Result layout = MasterLayout.compute(current, catalog, config.layout());
        List<Row> rows = new ArrayList<>();
        for (String id : layout.order) {
            FilterEntry entry = catalog.find(id);
            String category = entry == null ? FilterEntry.Category.PRESET.displayName : entry.category.displayName;
            String title = entry != null && entry.title != null ? entry.title
                : cameraNames.containsKey(id) ? cameraNames.get(id)
                : CAMERA_NAMES.containsKey(id) ? CAMERA_NAMES.get(id) : id;
            rows.add(new Row(id, category, title, MasterLayout.isVisible(id, catalog, config.layout()),
                MasterLayout.STANDARD.equals(id), entry != null && entry.source == FilterEntry.Source.IMPORTED));
        }
        return rows;
    }

    /** Moves the row at from to position to (标准 stays first). */
    public synchronized void move(int from, int to) throws IOException {
        List<Row> rows = rows();
        if (from <= 0 || to <= 0 || from >= rows.size() || to >= rows.size() || from == to) return;
        List<String> order = ids(rows);
        order.add(to, order.remove(from));
        MasterConfig config = config();
        save(new MasterConfig(order, config.visible, config.imported));
    }

    public synchronized void setVisible(String id, boolean visible) throws IOException {
        if (MasterLayout.STANDARD.equals(id)) return;
        MasterConfig config = config();
        Map<String, Boolean> map = new LinkedHashMap<>(config.visible);
        map.put(id, visible);
        save(new MasterConfig(config.order, map, config.imported));
    }

    // ---- import / delete ------------------------------------------------------------------------

    /** Converts a Rec.709 .cube and adds it as an imported filter; returns its ID. */
    public synchronized String importCube(String fileName, InputStream cube, byte[] header) throws IOException {
        Rec709LutBaker.Cube parsed = Rec709LutBaker.parse(new InputStreamReader(cube, StandardCharsets.UTF_8));
        byte[] lut = Rec709LutBaker.bake(parsed, header);
        String id = MasterConfig.IMPORT_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT)
            + MasterConfig.LUT_SUFFIX;
        File file = lutFile(id);
        write(file, lut);
        MasterConfig config = config();
        List<MasterConfig.Imported> imported = new ArrayList<>(config.imported);
        imported.add(new MasterConfig.Imported(id, titleOf(fileName), lut.length));
        save(new MasterConfig(config.order, config.visible, imported));
        return id;
    }

    public synchronized void deleteImported(String id) throws IOException {
        MasterConfig config = config();
        List<MasterConfig.Imported> imported = new ArrayList<>();
        for (MasterConfig.Imported item : config.imported) if (!item.id.equals(id)) imported.add(item);
        List<String> order = new ArrayList<>(config.order);
        order.remove(id);
        Map<String, Boolean> visible = new LinkedHashMap<>(config.visible);
        visible.remove(id);
        save(new MasterConfig(order, visible, imported));
        lutFile(id).delete();
    }

    static String titleOf(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".cube")) name = name.substring(0, name.length() - 5);
        name = name.trim();
        return name.isEmpty() ? "LUT" : name;
    }

    // ---- backup ---------------------------------------------------------------------------------

    /** Writes config.txt and every imported LUT into a zip archive. */
    public synchronized void backup(OutputStream out) throws IOException {
        MasterConfig config = config();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(BACKUP_CONFIG));
            zip.write(config.write().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (MasterConfig.Imported item : config.imported) {
                File file = lutFile(item.id);
                if (!file.isFile()) continue;
                zip.putNextEntry(new ZipEntry(BACKUP_LUT + item.id));
                zip.write(Files.readAllBytes(file.toPath()));
                zip.closeEntry();
            }
        }
    }

    /**
     * Replaces the configuration and the imported LUTs with a backup. The archive is checked
     * first (config present, every imported LUT present and of the recorded size); nothing is
     * changed if it is not valid.
     */
    public synchronized void restore(InputStream in) throws IOException {
        String configText = null;
        Map<String, byte[]> luts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                byte[] data = readAll(zip, 64L << 20);
                if (BACKUP_CONFIG.equals(entry.getName())) configText = new String(data, StandardCharsets.UTF_8);
                else if (entry.getName().startsWith(BACKUP_LUT)) luts.put(entry.getName().substring(BACKUP_LUT.length()), data);
            }
        }
        if (configText == null) throw new IOException("Not a backup: config.txt missing");
        MasterConfig config = MasterConfig.parse(configText);
        for (MasterConfig.Imported item : config.imported) {
            byte[] data = luts.get(item.id);
            if (data == null || data.length != item.size) throw new IOException("Backup LUT missing or damaged: " + item.title);
        }
        Set<String> keep = new HashSet<>();
        for (MasterConfig.Imported item : config.imported) {
            write(lutFile(item.id), luts.get(item.id));
            keep.add(item.id);
        }
        File[] existing = dir.listFiles();
        for (File file : existing == null ? new File[0] : existing) {
            String name = file.getName();
            if (name.startsWith(LUT_PREFIX) && !keep.contains(name.substring(LUT_PREFIX.length()))) file.delete();
        }
        save(config);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static List<String> ids(List<Row> rows) {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) ids.add(row.id);
        return ids;
    }

    private static String read(File file) {
        try {
            return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException error) {
            return "";
        }
    }

    /** Writes through a temporary file, so readers never see a partial file; readable by all. */
    private static void write(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File temp = new File(parent, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        }
        temp.setReadable(true, false);
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) throw new IOException("Cannot write " + file);
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            total += n;
            if (total > limit) throw new IOException("Backup entry too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
