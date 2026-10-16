import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.MasterConfig;
import local.jiege.hook.filters.MasterLayout;
import local.jiege.hook.filters.ProParamsRecord;
import local.jiege.hook.filters.PresetSelection;

/**
 * Offline tests for local.jiege.hook.filters (plain JVM, no device). Run by
 * tools/offline_tests/run.ps1; exits non-zero on the first failure.
 */
public final class FiltersOfflineTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        presetSelection();
        catalogs();
        injector();
        reorderKeepsSelection();
        layout();
        config();
        proParams();
        mirror();
        System.out.println("PASS: " + passed + " checks");
    }

    // ---- PresetSelection ------------------------------------------------------------------

    private static void presetSelection() {
        List<String> filters = list("default", "qing_tou.bin", "hu_po.bin", "fuji.cube.rgb.bin");
        String sig = PresetSelection.signature(filters);
        List<String> keys = keys(2, filters);  // custom_0, custom_1, "", qing_tou, hu_po, fuji

        PresetSelection.Result r = PresetSelection.resolve(4, keys, 2, sig, null, null);
        check("first run trusts stored position", r.index == 4 && !r.relocated && r.key.equals("hu_po.bin") && r.signature.equals(sig));

        r = PresetSelection.resolve(5, keys, 2, sig, "hu_po.bin", sig);
        check("same list: user selection is trusted", r.index == 5 && !r.relocated && r.key.equals("fuji.cube.rgb.bin"));

        r = PresetSelection.resolve(0, keys, 2, sig, "hu_po.bin", sig);
        check("same list: custom preset selection is trusted", r.index == 0 && r.key.equals("custom_0"));

        List<String> reordered = list("default", "fuji.cube.rgb.bin", "qing_tou.bin", "hu_po.bin");
        List<String> reorderedKeys = keys(2, reordered);
        r = PresetSelection.resolve(4, reorderedKeys, 2, PresetSelection.signature(reordered), "hu_po.bin", sig);
        check("reordered list: selection follows the filter", r.relocated && r.index == 5 && r.key.equals("hu_po.bin"));

        List<String> inserted = list("default", "new.bin", "qing_tou.bin", "hu_po.bin", "fuji.cube.rgb.bin");
        r = PresetSelection.resolve(5, keys(2, inserted), 2, PresetSelection.signature(inserted), "fuji.cube.rgb.bin", sig);
        check("inserted filter: selection follows the filter", r.relocated && r.index == 6 && r.key.equals("fuji.cube.rgb.bin"));

        List<String> removed = list("default", "qing_tou.bin", "fuji.cube.rgb.bin");
        r = PresetSelection.resolve(4, keys(2, removed), 2, PresetSelection.signature(removed), "hu_po.bin", sig);
        check("removed filter: selection falls back to 标准", r.relocated && r.index == 2 && r.key.equals(""));

        r = PresetSelection.resolve(1, keys(2, reordered), 2, PresetSelection.signature(reordered), "custom_1", sig);
        check("list changed: custom preset selection kept", !r.relocated && r.index == 1 && r.key.equals("custom_1"));

        // The camera adds a custom preset: list gets one more custom entry and a1 points at it.
        List<String> oneMoreCustom = keys(3, filters);
        r = PresetSelection.resolve(2, oneMoreCustom, 3, sig, "hu_po.bin", sig);
        check("camera-added custom preset is trusted", r.index == 2 && !r.relocated && r.key.equals("custom_2"));

        r = PresetSelection.resolve(99, keys, 2, sig, "hu_po.bin", sig);
        check("out of range position falls back to 标准", r.relocated && r.index == 2);

        r = PresetSelection.resolve(-1, keys, 2, sig, null, null);
        check("negative position falls back to 标准", r.relocated && r.index == 2);

        r = PresetSelection.resolve(3, Collections.<String>emptyList(), 0, sig, "x", sig);
        check("empty preset list is left alone", r.index == 3 && !r.relocated);

        check("signature changes with order", !PresetSelection.signature(filters).equals(PresetSelection.signature(reordered)));
        check("signature is stable", PresetSelection.signature(filters).equals(PresetSelection.signature(new ArrayList<>(filters))));
    }

    // ---- Catalogs -------------------------------------------------------------------------

    private static void catalogs() {
        FilterCatalog normal = FilterCatalog.get(FilterScope.NORMAL);
        FilterCatalog master = FilterCatalog.get(FilterScope.MASTER);
        check("normal catalog: 清透, 琥珀", ids(normal.enabled()).equals(list("qing_tou.bin", "hu_po.bin")));
        check("master catalog: 39 entries, 34 shown by default", master.entries().size() == 39 && master.enabled().size() == 34);
        check("master catalog order", ids(master.entries()).subList(0, 13).equals(list(
            "qing_tou.bin", "hu_po.bin",
            "Radiance.cube.rgb.bin", "Emerald.cube.rgb.bin", "kodak.cube.rgb.bin", "vivid-warm.cube.rgb.bin", "fuji.cube.rgb.bin",
            "morandi.cube.rgb.bin", "blackandwhite.cube.rgb.bin", "mono.cube.rgb.bin", "cyberpunk-kitsch-3.1.CUBE.rgb.bin",
            "vivid-lut.cube.rgb.bin", "LeicaM9.cube.rgb.bin")));
        check("classic: picked five on, the rest off", master.find("kodak.cube.rgb.bin").enabled && !master.find("morandi.cube.rgb.bin").enabled
            && !master.find("vivid-lut.cube.rgb.bin").enabled);
        check("master portrait is not listed (its LUT is the stock one)", master.find("Serenity.cube.rgb.bin") == null);
        check("Nostalgic Neg. is a Fuji filter", "Nostalgic Neg.".equals(master.find("JG_FUJI_NOSTALGIC_NEG.cube.rgb.bin").title)
            && master.find("JG_FUJI_NOSTALGIC_NEG.cube.rgb.bin").category == FilterEntry.Category.FUJI);
        check("Ricoh filters", "理光滤镜".equals(master.find("JG_RICOH_CINEMA_GREEN.cube.rgb.bin").label)
            && "Cinema (Green)".equals(master.find("JG_RICOH_CINEMA_GREEN.cube.rgb.bin").title));
        check("film simulation: ten, no 800T, no Leica M9", count(master, FilterEntry.Category.FILM) == 10
            && master.find("JG_FILM_CINESTILL_800T.cube.rgb.bin") == null);
        check("Leica label", "徕卡风格".equals(master.find("LeicaM9.cube.rgb.bin").label));
        check("X10 is PRESET without label, restored is CLASSIC", master.find("hu_po.bin").category == FilterEntry.Category.PRESET
            && master.find("hu_po.bin").label == null && master.find("Emerald.cube.rgb.bin").category == FilterEntry.Category.CLASSIC);
        check("every module LUT borrows the Radiance capture ID", allModuleLutsBorrow(master));
        check("catalogs are separate objects", normal != master && normal.find("LUT65_A01.cube.rgb.bin") == null);
        check("X10 entries are HEAD in both scopes",
            normal.find("hu_po.bin").placement == FilterEntry.Placement.HEAD && master.find("hu_po.bin").placement == FilterEntry.Placement.HEAD);
        check("Fuji label", "富士滤镜".equals(master.find("LUT65_A05.cube.rgb.bin").label));
        check("ETERNA BB title", "ETERNA BB".equals(master.find("LUT65_A05.cube.rgb.bin").title));
        check("classic label and own capture ID", "旧版经典".equals(master.find("kodak.cube.rgb.bin").label)
            && master.find("kodak.cube.rgb.bin").captureIdFrom == null);
        check("module LUTs borrow the Radiance capture ID", "Radiance.cube.rgb.bin".equals(master.find("LeicaM9.cube.rgb.bin").captureIdFrom));
        check("X10 entries keep the camera name", normal.find("qing_tou.bin").title == null
            && "camera_filter_oplus_qing_tou".equals(normal.find("qing_tou.bin").nameResource));
        check("scope of master mode", FilterScope.ofMode("professional") == FilterScope.MASTER
            && FilterScope.ofMode("common") == FilterScope.NORMAL && FilterScope.ofMode(null) == FilterScope.NORMAL);
    }

    // ---- FilterGroupInjector --------------------------------------------------------------

    /** Stand-in for the camera's FilterGroup (addFrontAndBack / addBack, mBackTypeList). */
    public static final class FakeGroup {
        public final List<String> mBackTypeList = new ArrayList<>();
        public final List<String> mFrontTypeList = new ArrayList<>();
        public final List<Object> names = new ArrayList<>();

        FakeGroup(String... types) {
            for (String type : types) { mBackTypeList.add(type); mFrontTypeList.add(type); names.add("stock"); }
        }

        public void addFrontAndBack(String type, Object name) { addFrontAndBack(mBackTypeList.size(), type, name); }

        public void addFrontAndBack(int position, String type, Object name) {
            mBackTypeList.add(position, type);
            mFrontTypeList.add(Math.min(position, mFrontTypeList.size()), type);
            names.add(position, name);
        }

        public void addBack(String type, Object name) { addBack(mBackTypeList.size(), type, name); }

        public void addBack(int position, String type, Object name) {
            mBackTypeList.add(position, type);
            names.add(position, name);
        }
    }

    private static int inject(Object group, boolean backOnly, List<FilterEntry> entries) throws Exception {
        Class<?> injector = Class.forName("local.jiege.hook.filters.FilterGroupInjector");
        Method inject = injector.getDeclaredMethod("inject", Object.class, boolean.class, List.class, android.content.Context.class);
        inject.setAccessible(true);
        return (Integer) inject.invoke(null, group, backOnly, entries, null);
    }

    private static void injector() throws Exception {
        List<FilterEntry> master = FilterCatalog.get(FilterScope.MASTER).entries();
        FakeGroup pro = new FakeGroup("default", "portra400_master.bin", "positive_master.bin");
        int added = inject(pro, false, master);
        check("master: all 39 added, switched-off ones included", added == 39);
        check("master: X10 right after 标准", pro.mBackTypeList.subList(0, 3).equals(list("default", "qing_tou.bin", "hu_po.bin")));
        check("master: stock filters keep their order", pro.mBackTypeList.subList(3, 5).equals(list("portra400_master.bin", "positive_master.bin")));
        check("master: TAIL in catalog order", pro.mBackTypeList.get(5).equals("Radiance.cube.rgb.bin")
            && pro.mBackTypeList.get(pro.mBackTypeList.size() - 1).equals("JG_FILM_KODAK_ULTRAMAX_400.cube.rgb.bin"));
        check("master: SDK names are R.string references",
            "R.string.camera_filter_oplus_master_radiance_new".equals(pro.names.get(pro.mBackTypeList.indexOf("kodak.cube.rgb.bin"))));
        check("master: front list gets them too", pro.mFrontTypeList.size() == pro.mBackTypeList.size());
        List<String> before = new ArrayList<>(pro.mBackTypeList);
        check("master: second run adds nothing", inject(pro, false, master) == 0 && pro.mBackTypeList.equals(before));

        FakeGroup night = new FakeGroup("default", "stock_night.bin");
        inject(night, true, FilterCatalog.get(FilterScope.NORMAL).enabled());
        check("night: back only", night.mBackTypeList.equals(list("default", "qing_tou.bin", "hu_po.bin", "stock_night.bin"))
            && night.mFrontTypeList.equals(list("default", "stock_night.bin")));

        // A group that already has one X10 filter (e.g. injected earlier) keeps HEAD order.
        FakeGroup partial = new FakeGroup("default", "qing_tou.bin", "stock.bin");
        inject(partial, false, FilterCatalog.get(FilterScope.NORMAL).enabled());
        check("partial: missing HEAD entry goes after the present one",
            partial.mBackTypeList.equals(list("default", "qing_tou.bin", "hu_po.bin", "stock.bin")));
    }

    // ---- End to end: filter order change keeps the selected filter --------------------------

    private static void reorderKeepsSelection() throws Exception {
        FakeGroup pro = new FakeGroup("default", "portra400_master.bin");
        inject(pro, false, FilterCatalog.get(FilterScope.MASTER).entries());
        List<String> firstList = new ArrayList<>(pro.mBackTypeList);
        List<String> firstKeys = keys(1, firstList);
        int velvia = firstKeys.indexOf("LUT65_A10.cube.rgb.bin");
        PresetSelection.Result first = PresetSelection.resolve(velvia, firstKeys, 1, PresetSelection.signature(firstList), null, null);

        // Next launch: a stock filter appears before the module filters (e.g. firmware update).
        FakeGroup next = new FakeGroup("default", "portra400_master.bin", "new_stock.bin");
        inject(next, false, FilterCatalog.get(FilterScope.MASTER).entries());
        List<String> nextKeys = keys(1, next.mBackTypeList);
        PresetSelection.Result second = PresetSelection.resolve(velvia, nextKeys, 1,
            PresetSelection.signature(next.mBackTypeList), first.key, first.signature);
        check("end to end: Velvia stays selected after the list changed",
            second.relocated && nextKeys.get(second.index).equals("LUT65_A10.cube.rgb.bin"));
    }

    // ---- MasterLayout ---------------------------------------------------------------------------

    private static void layout() {
        FilterCatalog master = FilterCatalog.get(FilterScope.MASTER);
        List<String> current = list("default", "qing_tou.bin", "portra400_master.bin", "positive_master.bin",
            "kodak.cube.rgb.bin", "morandi.cube.rgb.bin", "LeicaM9.cube.rgb.bin");

        MasterLayout.Result r = MasterLayout.compute(current, master, null);
        check("defaults: switched-off classic moves to the end", r.hiddenCount == 1
            && r.order.equals(list("default", "qing_tou.bin", "portra400_master.bin", "positive_master.bin",
                "kodak.cube.rgb.bin", "LeicaM9.cube.rgb.bin", "morandi.cube.rgb.bin")));

        java.util.Map<String, Boolean> visible = new java.util.HashMap<>();
        visible.put("positive_master.bin", false);
        visible.put("morandi.cube.rgb.bin", true);
        visible.put("default", false);
        MasterLayout.Saved saved = new MasterLayout.Saved(list("LeicaM9.cube.rgb.bin", "gone.bin", "kodak.cube.rgb.bin", "qing_tou.bin"), visible);
        r = MasterLayout.compute(current, master, saved);
        check("saved: user order, unlisted after their camera predecessor, hidden last; standard first and shown",
            r.order.equals(list("default", "LeicaM9.cube.rgb.bin", "kodak.cube.rgb.bin", "morandi.cube.rgb.bin", "qing_tou.bin",
                "portra400_master.bin", "positive_master.bin")) && r.hiddenCount == 1);
        MasterLayout.Saved withoutStock = new MasterLayout.Saved(list("qing_tou.bin", "kodak.cube.rgb.bin", "morandi.cube.rgb.bin",
            "LeicaM9.cube.rgb.bin"), new java.util.HashMap<String, Boolean>());
        r = MasterLayout.compute(current, master, withoutStock);
        check("saved order without stock filters keeps them in place",
            r.order.subList(0, 5).equals(list("default", "qing_tou.bin", "portra400_master.bin", "positive_master.bin", "kodak.cube.rgb.bin")));
        check("saved: stock filter can be hidden", !MasterLayout.isVisible("positive_master.bin", master, saved));
        check("saved: IDs no longer present are ignored", !r.order.contains("gone.bin"));

        List<String> types = list("a", "b", "c", "d");
        List<Integer> names = new ArrayList<>(Arrays.asList(1, 2, 3, 4));
        MasterLayout.reorder(types, names, list("c", "a"));
        check("reorder keeps parallel lists aligned", types.equals(list("c", "a", "b", "d")) && names.equals(Arrays.asList(3, 1, 2, 4)));
        List<String> front = list("a", "d");
        List<Integer> frontNames = new ArrayList<>(Arrays.asList(1, 4));
        MasterLayout.reorder(front, frontNames, list("d", "b", "a"));
        check("reorder of a partial list", front.equals(list("d", "a")) && frontNames.equals(Arrays.asList(4, 1)));
    }

    // ---- MasterConfig -----------------------------------------------------------------------------

    private static void config() {
        java.util.Map<String, Boolean> visible = new java.util.LinkedHashMap<>();
        visible.put("morandi.cube.rgb.bin", true);
        visible.put("positive_master.bin", false);
        MasterConfig written = new MasterConfig(list("LeicaM9.cube.rgb.bin", "default"), visible,
            Arrays.asList(new MasterConfig.Imported("JG_IMPORT_ab12.cube.rgb.bin", "My Look", 98480)));
        MasterConfig read = MasterConfig.parse(written.write());
        check("config round trip", read.order.equals(written.order) && read.visible.equals(visible)
            && read.imported.size() == 1 && read.imported.get(0).title.equals("My Look") && read.imported.get(0).size == 98480);
        check("config: tab in title is removed", new MasterConfig.Imported("JG_IMPORT_x.cube.rgb.bin", "a\tb\nc", 1).title.equals("a b c"));
        check("config: wrong header gives empty", MasterConfig.parse("hello\norder\tx").order.isEmpty());
        check("config: malformed records are skipped",
            MasterConfig.parse("jiege-master 1\nimport\tbad.bin\tx\t1\nimport\tJG_IMPORT_a.cube.rgb.bin\tA\tnope\norder\ty").imported.isEmpty());
        check("config: import IDs must be safe", !MasterConfig.isImportId("JG_IMPORT_../x.cube.rgb.bin")
            && !MasterConfig.isImportId("LeicaM9.cube.rgb.bin") && MasterConfig.isImportId("JG_IMPORT_a1.cube.rgb.bin"));

        FilterCatalog.configure(read);
        FilterCatalog master = FilterCatalog.get(FilterScope.MASTER);
        FilterEntry imported = master.find("JG_IMPORT_ab12.cube.rgb.bin");
        check("imported filter in the master catalog", imported != null && imported.category == FilterEntry.Category.IMPORTED
            && "导入滤镜".equals(imported.label) && "Radiance.cube.rgb.bin".equals(imported.captureIdFrom)
            && master.entries().get(master.entries().size() - 1) == imported);
        check("imported filter not in the normal catalog", FilterCatalog.get(FilterScope.NORMAL).find("JG_IMPORT_ab12.cube.rgb.bin") == null);
        FilterCatalog.configure(null);
        check("configure(null) drops imports", FilterCatalog.get(FilterScope.MASTER).find("JG_IMPORT_ab12.cube.rgb.bin") == null);
    }

    // ---- NormalMasterMirror ---------------------------------------------------------------------

    private static void mirror() throws Exception {
        java.util.Map<String, Boolean> visible = new java.util.LinkedHashMap<>();
        visible.put("LUT65_A01.cube.rgb.bin", false);
        visible.put("morandi.cube.rgb.bin", true);
        FilterCatalog.configure(new MasterConfig(list("LeicaM9.cube.rgb.bin"), visible,
            Arrays.asList(new MasterConfig.Imported("JG_IMPORT_ab12.cube.rgb.bin", "My Look", 1))), true);
        FilterCatalog normal = FilterCatalog.get(FilterScope.NORMAL);
        FilterCatalog master = FilterCatalog.get(FilterScope.MASTER);
        check("mirror: master filters shown in the master list are in the normal catalog",
            normal.find("LeicaM9.cube.rgb.bin") == master.find("LeicaM9.cube.rgb.bin") && normal.find("kodak.cube.rgb.bin") != null
            && normal.find("JG_IMPORT_ab12.cube.rgb.bin") != null && normal.find("morandi.cube.rgb.bin") != null);
        check("mirror: filters hidden in the master list are left out", normal.find("LUT65_A01.cube.rgb.bin") == null
            && normal.find("mono.cube.rgb.bin") == null);
        check("mirror: X10 entries stay single", Collections.frequency(ids(normal.entries()), "qing_tou.bin") == 1);

        Method takes = Class.forName("local.jiege.hook.filters.NormalMasterMirror").getDeclaredMethod("takes", String.class, FilterEntry.class);
        takes.setAccessible(true);
        FilterEntry leica = normal.find("LeicaM9.cube.rgb.bin");
        check("mirror: extras go to photo and portrait only", (Boolean) takes.invoke(null, "sFilterGroup", leica)
            && (Boolean) takes.invoke(null, "sPortraitFilterGroup", leica)
            && !(Boolean) takes.invoke(null, "sStickerFilterGroup", leica) && !(Boolean) takes.invoke(null, "sNightFilterGroup", leica)
            && (Boolean) takes.invoke(null, "sNightFilterGroup", normal.find("hu_po.bin")));

        Method order = Class.forName("local.jiege.hook.filters.NormalMasterMirror").getDeclaredMethod("order", List.class, int.class);
        order.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> mapped = (List<String>) order.invoke(null,
            list("default", "qing_tou.bin", "fuji-nc_master.bin", "portra400_master.bin", "LeicaM9.cube.rgb.bin", "positive_master.bin"), 1);
        check("mirror: master types map to the normal assets, hidden ones are dropped",
            mapped.equals(list("default", "qing_tou.bin", "fuji-nc.bin", "portra400_normal.bin", "LeicaM9.cube.rgb.bin")));

        FakeGroup photo = new FakeGroup("default", "portra400_normal.bin", "positive_normal.bin", "fuji-nc.bin", "mountains.cube.rgb.bin");
        inject(photo, false, normal.enabled());
        MasterLayout.reorder(photo.mBackTypeList, photo.names, mapped);
        check("mirror: photo group follows the master order, the rest keeps its place",
            photo.mBackTypeList.subList(0, 5).equals(list("default", "qing_tou.bin", "fuji-nc.bin", "portra400_normal.bin", "LeicaM9.cube.rgb.bin"))
            && photo.mBackTypeList.indexOf("positive_normal.bin") < photo.mBackTypeList.indexOf("mountains.cube.rgb.bin")
            && photo.mBackTypeList.indexOf("hu_po.bin") > 4);
        FilterCatalog.configure(null);
        check("mirror off: normal catalog is X10 only", ids(FilterCatalog.get(FilterScope.NORMAL).entries()).equals(list("qing_tou.bin", "hu_po.bin")));
    }

    private static void proParams() {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put(ProParamsRecord.ISO, "400");
        values.put(ProParamsRecord.EXPOSURE_TIME, "1/125");
        values.put(ProParamsRecord.COLOR_TONE, "warm	ish");
        values.put("pref_future_param", "x");
        java.util.Map<String, String> read = ProParamsRecord.parse(ProParamsRecord.write(values));
        check("pro params round trip", "400".equals(read.get(ProParamsRecord.ISO)) && "1/125".equals(read.get(ProParamsRecord.EXPOSURE_TIME))
            && "warm ish".equals(read.get(ProParamsRecord.COLOR_TONE)) && "x".equals(read.get("pref_future_param")));
        check("pro params: empty record means none", ProParamsRecord.parse("").isEmpty() && ProParamsRecord.parse(null).isEmpty());
        local.jiege.hook.filters.MasterPreset.ProParams typed = ProParamsRecord.toProParams(read);
        check("pro params typed view", "400".equals(typed.iso) && "1/125".equals(typed.shutter) && typed.whiteBalance == null
            && typed.extra.containsKey("pref_future_param") && typed.extra.containsKey(ProParamsRecord.COLOR_TONE));
    }

    private static int count(FilterCatalog catalog, FilterEntry.Category category) {
        int n = 0;
        for (FilterEntry entry : catalog.entries()) if (entry.category == category) n++;
        return n;
    }

    private static boolean allModuleLutsBorrow(FilterCatalog catalog) {
        for (FilterEntry entry : catalog.entries()) {
            if (entry.source == FilterEntry.Source.MODULE_LUT && !"Radiance.cube.rgb.bin".equals(entry.captureIdFrom)) return false;
        }
        return true;
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Camera preset keys: custom_0..custom_{n-1}, then "" for 标准 and the filter types. */
    private static List<String> keys(int customCount, List<String> filters) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < customCount; i++) out.add("custom_" + i);
        for (int i = 0; i < filters.size(); i++) out.add(i == 0 ? "" : filters.get(i));
        return out;
    }

    private static List<String> ids(List<FilterEntry> entries) {
        List<String> out = new ArrayList<>();
        for (FilterEntry entry : entries) out.add(entry.id);
        return out;
    }

    private static List<String> list(String... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            System.out.println("FAIL: " + name);
            System.exit(1);
        }
        passed++;
    }
}
