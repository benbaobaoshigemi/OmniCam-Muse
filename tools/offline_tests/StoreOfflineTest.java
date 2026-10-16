import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import local.jiege.hook.filters.MasterConfig;
import local.jiege.settings.MasterFilterStore;

/**
 * Offline tests for the settings app's master filter store: list assembly, reorder, visibility,
 * import (real .cube), delete, backup and restore. Args: project directory.
 */
public final class StoreOfflineTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        byte[] header = Files.readAllBytes(project.resolve("app/src/main/assets/mslut_header.bin"));
        File dir = Files.createTempDirectory("jiege_store").toFile();
        MasterFilterStore store = new MasterFilterStore(dir);

        List<MasterFilterStore.Row> rows = store.rows();
        check("without a camera report: 标准 first, then the catalog", rows.get(0).id.equals("default")
            && rows.get(0).displayName().equals("预置滤镜-标准") && rows.get(0).fixed && !store.hasCameraList());

        store.saveCameraList("default\t标准\nqing_tou.bin\t\nSerenity.cube.rgb.bin\t人文\nportra400_master.bin\t和光\n"
            + "Radiance.cube.rgb.bin\t\nLeicaM9.cube.rgb.bin\t\nmorandi.cube.rgb.bin\t\n");
        rows = store.rows();
        check("camera report is used", store.hasCameraList() && name(rows, "Serenity.cube.rgb.bin").equals("预置滤镜-人文"));
        check("category names", name(rows, "Radiance.cube.rgb.bin").equals("旧版经典-大师童话")
            && name(rows, "LeicaM9.cube.rgb.bin").equals("徕卡风格-徕卡M9")
            && name(rows, "LUT65_A01.cube.rgb.bin").equals("富士滤镜-ACROS")
            && name(rows, "JG_RICOH_VIVID.cube.rgb.bin").equals("理光滤镜-Vivid")
            && name(rows, "JG_FILM_KODAK_PORTRA_400.cube.rgb.bin").equals("胶片模拟-Kodak Portra 400")
            && name(rows, "qing_tou.bin").equals("预置滤镜-清透"));
        check("switched-off classic listed, hidden, at the end", !row(rows, "morandi.cube.rgb.bin").visible
            && index(rows, "morandi.cube.rgb.bin") > index(rows, "JG_FILM_KODAK_ULTRAMAX_400.cube.rgb.bin"));

        int leica = index(rows, "LeicaM9.cube.rgb.bin");
        store.move(leica, 1);
        rows = store.rows();
        check("move: Leica right after 标准", rows.get(1).id.equals("LeicaM9.cube.rgb.bin") && rows.get(0).id.equals("default"));
        store.move(1, 0);
        check("move: nothing moves onto 标准", store.rows().get(0).id.equals("default"));

        store.setVisible("Serenity.cube.rgb.bin", false);
        store.setVisible("morandi.cube.rgb.bin", true);
        store.setVisible("default", false);
        rows = store.rows();
        check("visibility: stock filter hidden and moved to the hidden tail", !row(rows, "Serenity.cube.rgb.bin").visible
            && index(rows, "Serenity.cube.rgb.bin") == rows.size() - countHidden(rows) + hiddenRank(rows, "Serenity.cube.rgb.bin"));
        check("visibility: switched-off classic can be shown", row(rows, "morandi.cube.rgb.bin").visible);
        check("visibility: 标准 cannot be hidden", row(rows, "default").visible);
        check("visibility keeps the order of the rest", rows.get(1).id.equals("LeicaM9.cube.rgb.bin"));

        String id;
        try (InputStream cube = Files.newInputStream(project.resolve("inputs/master_luts/Green.cube"))) {
            id = store.importCube("content://x/Documents/My Green.cube", cube, header);
        }
        rows = store.rows();
        byte[] expected = Files.readAllBytes(project.resolve("module/payload/meishe_lut/JG_RICOH_CINEMA_GREEN.cube.rgb.bin"));
        check("import: ID, title and category", MasterConfig.isImportId(id) && name(rows, id).equals("导入滤镜-My Green") && row(rows, id).imported);
        check("import: LUT converted like the module's", java.util.Arrays.equals(Files.readAllBytes(store.lutFile(id).toPath()), expected));
        check("import: config records it", store.config().imported.size() == 1 && store.config().imported.get(0).size == expected.length);
        boolean rejected = false;
        try {
            store.importCube("bad.cube", new ByteArrayInputStream("LUT_3D_SIZE 2\n0 0 0\n".getBytes()), header);
        } catch (IOException expectedError) {
            rejected = true;
        }
        check("import: a broken .cube is rejected and not recorded", rejected && store.config().imported.size() == 1);

        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        store.backup(backup);
        List<String> orderBefore = ids(store.rows());

        store.deleteImported(id);
        rows = store.rows();
        check("delete: gone from list, config and disk", index(rows, id) < 0 && store.config().imported.isEmpty() && !store.lutFile(id).exists());

        store.restore(new ByteArrayInputStream(backup.toByteArray()));
        rows = store.rows();
        check("restore: imported filter, LUT and order are back", index(rows, id) >= 0
            && java.util.Arrays.equals(Files.readAllBytes(store.lutFile(id).toPath()), expected) && ids(rows).equals(orderBefore));

        boolean refused = false;
        try {
            store.restore(new ByteArrayInputStream("not a zip".getBytes()));
        } catch (IOException expectedError) {
            refused = true;
        }
        check("restore: invalid archive refused, nothing changed", refused && index(store.rows(), id) >= 0);

        // A backup whose LUT does not match the recorded size is refused.
        ByteArrayOutputStream broken = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(broken)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("config.txt"));
            zip.write(store.config().write().getBytes("UTF-8"));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("lut/" + id));
            zip.write(new byte[10]);
            zip.closeEntry();
        }
        refused = false;
        try {
            store.restore(new ByteArrayInputStream(broken.toByteArray()));
        } catch (IOException expectedError) {
            refused = true;
        }
        check("restore: damaged LUT refused, current LUT kept", refused
            && java.util.Arrays.equals(Files.readAllBytes(store.lutFile(id).toPath()), expected));

        store.saveCameraList("default\t标准\n" + id + "\t\nJG_IMPORT_DELETED00.cube.rgb.bin\t\n");
        check("camera report of a deleted import is ignored", index(store.rows(), "JG_IMPORT_DELETED00.cube.rgb.bin") < 0);

        System.out.println("PASS: " + passed + " store checks");
    }

    private static MasterFilterStore.Row row(List<MasterFilterStore.Row> rows, String id) {
        for (MasterFilterStore.Row row : rows) if (row.id.equals(id)) return row;
        throw new AssertionError("missing row " + id);
    }

    private static String name(List<MasterFilterStore.Row> rows, String id) {
        return row(rows, id).displayName();
    }

    private static int index(List<MasterFilterStore.Row> rows, String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
        return -1;
    }

    private static int countHidden(List<MasterFilterStore.Row> rows) {
        int n = 0;
        for (MasterFilterStore.Row row : rows) if (!row.visible) n++;
        return n;
    }

    private static int hiddenRank(List<MasterFilterStore.Row> rows, String id) {
        int n = 0;
        for (MasterFilterStore.Row row : rows) {
            if (row.visible) continue;
            if (row.id.equals(id)) return n;
            n++;
        }
        return -1;
    }

    private static List<String> ids(List<MasterFilterStore.Row> rows) {
        List<String> out = new ArrayList<>();
        for (MasterFilterStore.Row row : rows) out.add(row.id);
        return out;
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            System.out.println("FAIL: " + name);
            System.exit(1);
        }
        passed++;
    }
}
