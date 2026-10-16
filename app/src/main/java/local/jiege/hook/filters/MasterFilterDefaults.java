package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in filters of the master scope, in default display order (see MASTER_FILTER_AUDIT.md):
 *   - 清透, 琥珀 (X10), right after 标准;
 *   - 旧版经典: stock filters older master lists showed; stock LUTs in /odm/etc/camera/filters_lut
 *     with their own capture IDs. The five the user picked are on, the rest off by default.
 *     (大师人像 is left out: its LUT is 人文's, and a type can be listed only once.);
 *   - 徕卡风格, 富士滤镜, 理光滤镜, 胶片模拟: module LUTs (payload meishe_lut, baked by
 *     tools/bake_master_luts.py), capture ID borrowed from 大师童话 (same .cube.rgb.bin kind);
 *   - 导入滤镜: the user's imported LUTs (MasterConfig), same capture ID as the module LUTs.
 * Added LUTs are registered with 大师童话's name resource and shown with their titles.
 * IDs are the LUT file names and never change once shipped.
 */
final class MasterFilterDefaults {
    /** Resource the added LUTs are registered with; the titles replace its text. */
    private static final String NAME_RESOURCE = "camera_filter_oplus_master_radiance_new";
    /** Capture ID donor for .cube.rgb.bin LUTs without their own ID (大师童话). */
    private static final String CUBE_LUT_ID = "Radiance.cube.rgb.bin";

    private MasterFilterDefaults() {}

    static List<FilterEntry> entries(MasterConfig config) {
        List<FilterEntry> list = new ArrayList<>();
        X10Filters.add(list);

        classic(list, "Radiance.cube.rgb.bin", "大师童话", true);
        classic(list, "Emerald.cube.rgb.bin", "大师风光", true);
        classic(list, "kodak.cube.rgb.bin", "菲林", true);
        classic(list, "vivid-warm.cube.rgb.bin", "暖调", true);
        classic(list, "fuji.cube.rgb.bin", "胶片", true);
        classic(list, "morandi.cube.rgb.bin", "高级灰", false);
        classic(list, "blackandwhite.cube.rgb.bin", "黑白", false);
        classic(list, "mono.cube.rgb.bin", "单色", false);
        classic(list, "cyberpunk-kitsch-3.1.CUBE.rgb.bin", "赛博朋克", false);
        classic(list, "vivid-lut.cube.rgb.bin", "灰调", false);

        module(list, "LeicaM9.cube.rgb.bin", "徕卡M9", FilterEntry.Category.LEICA);

        String[] fuji = {"ACROS", "ASTIA", "Classic Chrome", "ETERNA", "ETERNA BB", "Classic Neg.",
            "PRO Neg. Std", "PROVIA", "REALA ACE", "Velvia"};
        for (int i = 0; i < fuji.length; i++) {
            module(list, String.format(Locale.ROOT, "LUT65_A%02d.cube.rgb.bin", i + 1), fuji[i], FilterEntry.Category.FUJI);
        }
        module(list, "JG_FUJI_NOSTALGIC_NEG.cube.rgb.bin", "Nostalgic Neg.", FilterEntry.Category.FUJI);

        module(list, "JG_RICOH_POSITIVE.cube.rgb.bin", "Positive", FilterEntry.Category.RICOH);
        module(list, "JG_RICOH_NEGATIVE.cube.rgb.bin", "Negative", FilterEntry.Category.RICOH);
        module(list, "JG_RICOH_CINEMA_GREEN.cube.rgb.bin", "Cinema (Green)", FilterEntry.Category.RICOH);
        module(list, "JG_RICOH_CINEMA_YELLOW.cube.rgb.bin", "Cinema (Yellow)", FilterEntry.Category.RICOH);
        module(list, "JG_RICOH_VIVID.cube.rgb.bin", "Vivid", FilterEntry.Category.RICOH);

        module(list, "JG_FILM_CINESTILL_50D.cube.rgb.bin", "CineStill 50D", FilterEntry.Category.FILM);
        module(list, "JG_FILM_FUJI_C200.cube.rgb.bin", "Fujicolor C200", FilterEntry.Category.FILM);
        module(list, "JG_FILM_HASSELBLAD_BLUE.cube.rgb.bin", "Hasselblad Blue", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_5203.cube.rgb.bin", "Kodak 5203", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_5207.cube.rgb.bin", "Kodak 5207", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_5219.cube.rgb.bin", "Kodak 5219", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_E200.cube.rgb.bin", "Kodak E200", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_GOLD_200.cube.rgb.bin", "Kodak Gold 200", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_PORTRA_400.cube.rgb.bin", "Kodak Portra 400", FilterEntry.Category.FILM);
        module(list, "JG_FILM_KODAK_ULTRAMAX_400.cube.rgb.bin", "Kodak UltraMax 400", FilterEntry.Category.FILM);

        for (MasterConfig.Imported item : config.imported) {
            list.add(new FilterEntry(item.id, NAME_RESOURCE, item.title, FilterEntry.Category.IMPORTED,
                FilterEntry.Source.IMPORTED, FilterEntry.Placement.TAIL, CUBE_LUT_ID, true));
        }
        return list;
    }

    private static void classic(List<FilterEntry> list, String id, String title, boolean enabled) {
        list.add(new FilterEntry(id, NAME_RESOURCE, title, FilterEntry.Category.CLASSIC,
            FilterEntry.Source.STOCK_RESTORED, FilterEntry.Placement.TAIL, null, enabled));
    }

    private static void module(List<FilterEntry> list, String id, String title, FilterEntry.Category category) {
        list.add(new FilterEntry(id, NAME_RESOURCE, title, category, FilterEntry.Source.MODULE_LUT,
            FilterEntry.Placement.TAIL, CUBE_LUT_ID, true));
    }
}
