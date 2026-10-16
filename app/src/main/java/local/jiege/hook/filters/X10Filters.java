package local.jiege.hook.filters;

import java.util.List;

/**
 * The X10 filters 清透 and 琥珀 as catalog entries. Both scopes list them, each in its own
 * catalog; X10FilterPort keeps the X10-specific parts (capture filter IDs 123/124, palette,
 * drawing items), which are keyed by type and so shared.
 */
public final class X10Filters {
    public static final String QING_TOU = "qing_tou.bin";
    public static final String HU_PO = "hu_po.bin";

    private X10Filters() {}

    static void add(List<FilterEntry> list) {
        list.add(entry(QING_TOU, "camera_filter_oplus_qing_tou"));
        list.add(entry(HU_PO, "camera_filter_oplus_hu_po"));
    }

    private static FilterEntry entry(String id, String nameResource) {
        return new FilterEntry(id, nameResource, null, FilterEntry.Category.PRESET, FilterEntry.Source.X10_PORT,
            FilterEntry.Placement.HEAD, null, true);
    }
}
