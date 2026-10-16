package local.jiege.hook.gr;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import local.jiege.hook.common.Symbols;

/**
 * Mode arrangement ("长按拖动即可调整顺序"): the camera's mode item (ModeItem, built per mode as
 * (Context, mode id, ...)) pins photo (20), video (21), GR (39) and vibe (41): two final flags it
 * sets false for them keep the item from being picked up, and its text colour is the 10 % white.
 * For GR the flags are set back and the colour is the normal one, so GR moves like other modes.
 * Every other final boolean of the item is true for GR, so the false ones are exactly those two.
 */
final class GrModeArrange {
    private static final int GR_MODE_ID = 39;

    private GrModeArrange() {}

    static void install(Symbols symbols) {
        Class<?> item = symbols.cls("ModeItem");
        XposedBridge.hookAllConstructors(item, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args.length < 2 || !(param.args[0] instanceof Context) || !Integer.valueOf(GR_MODE_ID).equals(param.args[1])) return;
                Context context = (Context) param.args[0];
                int dim = color(context, "color_white_with_10_percent_transparency");
                int full = color(context, "color_white_with_full_percent_transparency");
                for (Field field : item.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (field.getType() == boolean.class && Modifier.isFinal(field.getModifiers()) && !field.getBoolean(param.thisObject)) {
                        field.setBoolean(param.thisObject, true);
                    } else if (field.getType() == int.class && !Modifier.isFinal(field.getModifiers()) && dim != full && field.getInt(param.thisObject) == dim) {
                        field.setInt(param.thisObject, full);
                    }
                }
            }
        });
    }

    private static int color(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "color", context.getPackageName());
        return id == 0 ? 0 : context.getColor(id);
    }
}
