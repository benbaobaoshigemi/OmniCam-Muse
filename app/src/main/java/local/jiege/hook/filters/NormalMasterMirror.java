package local.jiege.hook.filters;

import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "拍照模式同步大师滤镜列表": photo, portrait and 哈苏超清 (which uses photo mode's group) list the
 * master filters in the master order, so the master filter configuration of the settings app
 * (order, visibility, imported LUTs) applies to them as well. Only the list changes:
 *   - Filters the master scope adds (旧版经典, 徕卡, 富士, 理光, 胶片, imported) become entries of the
 *     normal catalog, shown or hidden as in the master list, and are added to these two groups
 *     only (not to sticker or night). They share the entry objects with the master catalog, so
 *     titles, label cards and capture IDs need no second copy.
 *   - Stock filters keep the normal mode's own assets. Where the master mode uses a different
 *     file for the same filter (fuji-nc_master.bin ...), the normal one takes the master
 *     filter's place in the order. Stock filters only the normal mode has, and stock filters the
 *     user hid in the master list, follow the ordered ones in their own order.
 * Off by default (FilterCatalog.configure); applies when the camera starts.
 */
final class NormalMasterMirror {
    /** The groups that follow the master list: photo (also used by 哈苏超清) and portrait. */
    private static final List<String> GROUPS = java.util.Arrays.asList("sFilterGroup", "sPortraitFilterGroup");

    /** Master stock filter type -> the normal mode's type for the same filter (different asset). */
    private static final Map<String, String> NORMAL_TYPE = new HashMap<>();

    static {
        NORMAL_TYPE.put("portra400_master.bin", "portra400_normal.bin");
        NORMAL_TYPE.put("positive_master.bin", "positive_normal.bin");
        NORMAL_TYPE.put("fuji_cc_master.bin", "fuji_cc.bin");
        NORMAL_TYPE.put("fuji-nc_master.bin", "fuji-nc.bin");
        NORMAL_TYPE.put("fuji-proNegHi_master.bin", "fuji-proNegHi.bin");
        NORMAL_TYPE.put("800t_master.bin", "800t.bin");
        NORMAL_TYPE.put("blackandwhite_HC_master.bin", "blackandwhite_HC_normal.bin");
    }

    private NormalMasterMirror() {}

    /**
     * The master entries the normal catalog takes over: shown in the master list (per the user's
     * layout), and not already in the normal catalog (the X10 filters are in both).
     */
    static List<FilterEntry> mirrored(List<FilterEntry> masterEntries, List<FilterEntry> normalEntries, MasterLayout.Saved saved) {
        List<FilterEntry> out = new ArrayList<>();
        for (FilterEntry entry : masterEntries) {
            if (entry.source == FilterEntry.Source.X10_PORT || !MasterLayout.isVisible(entry.id, entry, saved)) continue;
            boolean own = false;
            for (FilterEntry normal : normalEntries) own |= normal.id.equals(entry.id);
            if (!own) out.add(entry);
        }
        return out;
    }

    /** Whether a filter group takes this normal catalog entry: mirrored ones go to the photo and portrait groups only. */
    static boolean takes(String groupName, FilterEntry entry) {
        return entry.source == FilterEntry.Source.X10_PORT || GROUPS.contains(groupName);
    }

    /**
     * The normal groups' order for a master order: the master types that are shown, each as the
     * normal mode's type for it. Types a group lacks are skipped by MasterLayout.reorder.
     */
    static List<String> order(List<String> masterOrder, int hiddenCount) {
        List<String> out = new ArrayList<>();
        for (String type : masterOrder.subList(0, masterOrder.size() - hiddenCount)) {
            String normal = NORMAL_TYPE.get(type);
            out.add(normal == null ? type : normal);
        }
        return out;
    }

    /** Orders the mirroring groups of a filter group manager by the (already laid out) Pro group; returns a log note. */
    @SuppressWarnings("unchecked")
    static String apply(Class<?> manager) {
        Object pro = XposedHelpers.getStaticObjectField(manager, "sProFilterGroup");
        if (pro == null) return "";
        List<String> masterOrder = new ArrayList<>((List<String>) XposedHelpers.getObjectField(pro, "mBackTypeList"));
        List<String> order = order(masterOrder, FilterGroupInjector.masterHiddenCount());
        int done = 0;
        for (String groupName : GROUPS) {
            Object group = XposedHelpers.getStaticObjectField(manager, groupName);
            if (group == null) continue;
            MasterLayout.reorder((List<String>) XposedHelpers.getObjectField(group, "mBackTypeList"),
                (List<Object>) XposedHelpers.getObjectField(group, "mBackNameIdList"), order);
            MasterLayout.reorder((List<String>) XposedHelpers.getObjectField(group, "mFrontTypeList"),
                (List<Object>) XposedHelpers.getObjectField(group, "mFrontNameIdList"), order);
            done++;
        }
        return ", " + done + " groups in master order";
    }
}
