import java.util.HashSet;
import java.util.Set;
import local.jiege.settings.MasterLooks;
import local.jiege.settings.ModuleSettings;

public final class MasterLooksOfflineTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        String[] legacy = {"kodak_portra_400", "kodak_gold_200", "kodak_ektar_100", "fujifilm_pro_400h",
            "fujifilm_velvia_100", "kodak_kodachrome_64", "kodak_vision3_500t"};
        for (int i = 0; i < legacy.length; i++) {
            check(MasterLooks.FILM_IDS[i].equals(legacy[i]), "persisted film index " + i);
            check(MasterLooks.filmLookId(i, 0).equals("film_" + legacy[i]), "default selection " + i);
        }
        Set<String> hncs = new HashSet<>();
        for (int i=0; i<2; i++) for (int c=0; c<2; c++) hncs.add(MasterLooks.hncsLookId(i,c));
        check(hncs.size() == 4, "HNCS choice matrix");
        check(MasterLooks.LUMIX_IDS.length == 15, "non-Log Lumix coverage");
        check(MasterLooks.CANON_IDS.length == 6, "Canon coverage");
        check(MasterLooks.FAMILY_LEICA == 8 && MasterLooks.FAMILY_FUJI == 9, "persisted family indices");
        check(MasterLooks.VISIBLE_FAMILIES.length == MasterLooks.FAMILY_LABELS.length, "visible family labels");
        for (int family : MasterLooks.VISIBLE_FAMILIES)
            check(family != MasterLooks.FAMILY_AGX && family != MasterLooks.FAMILY_FUJI, "removed top-level families");
        check(MasterLooks.lookId(MasterLooks.FAMILY_AGX,0,0,0,0,0,0,0,0,0).equals("agx"), "legacy AgX selection");
        check(MasterLooks.lookId(MasterLooks.FAMILY_DARKTABLE,0,0,0,0,2,0,0,0,0).equals("agx"), "Darktable AgX selection");
        check(MasterLooks.lookId(MasterLooks.FAMILY_FUJI,0,0,0,0,0,0,0,0,0).equals("hncs"), "removed Fuji selection");
        check(MasterLooks.lookId(MasterLooks.FAMILY_BASIC,0,0,0,0,0,3,0,0,0).equals("basic_gamma"), "Basic Gamma selection");
        check(MasterLooks.VISIBLE_FAMILIES[0] == MasterLooks.FAMILY_OPPO, "OPPO family is listed first");
        String[][] parametric = {{"basic_gamma", "1"}, {"sigmoid", "2"}, {"filmic", "2"}, {"agx", "4"}, {"hncs", "0"}, {"basic_rec709", "0"}};
        for (String[] entry : parametric) {
            MasterLooks.Param[] params = MasterLooks.paramsFor(entry[0]);
            check(params.length == Integer.parseInt(entry[1]), entry[0] + " parameter count");
            for (MasterLooks.Param p : params) {
                check(p.min < p.max && p.def >= p.min && p.def <= p.max, entry[0] + " " + p.title + " range");
                check(ModuleSettings.defaultInt(p.key) == p.def, entry[0] + " " + p.title + " default");
            }
        }
        check(MasterLooks.paramsFor("agx")[0].def == -1000 && MasterLooks.paramsFor("filmic")[1].def == 456, "AgX / Filmic defaults are the engines' own");
        check(MasterLooks.VISIBLE_FAMILIES.length == MasterLooks.FAMILY_LABELS.length
            && MasterLooks.VISIBLE_FAMILIES.length == MasterLooks.FAMILY_SUMMARIES.length, "family lists align");
        check(MasterLooks.lookId(MasterLooks.FAMILY_LEICA,0,0,0,0,0,0,0,0,0).equals("leica_m9"), "M9 selection");
        Set<String> combinations = new HashSet<>();
        int positive = 0;
        for (int f = 0; f < MasterLooks.FILM_IDS.length; f++) {
            if (MasterLooks.isPositiveFilm(f)) {
                positive++;
                for (int p = 0; p < MasterLooks.PRINT_IDS.length; p++)
                    check(MasterLooks.filmLookId(f, p).equals(MasterLooks.filmLookId(f, 0)), "positive ignores print");
                combinations.add(MasterLooks.filmLookId(f, 0));
            } else {
                for (int p = 1; p < MasterLooks.PRINT_IDS.length; p++)
                    check(combinations.add(MasterLooks.filmLookId(f, p)), "negative print pair must be unique");
            }
        }
        check(positive == 4 && combinations.size() == 132, "film selection matrix");
        check(ModuleSettings.defaultInt(ModuleSettings.MASTER_LOOK_PRINT) == 0, "upgrade print default");
        check(ModuleSettings.defaultInt(ModuleSettings.MASTER_FILM_DENSITY_C) == 100, "density default");
        System.out.println("PASS MasterLooks: persisted indices, 132 combinations, 15 Lumix styles, 6 Canon styles");
    }
}
