package local.jiege.hook.gallery;

import android.os.Build;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery photo editor: the hasselblad, LUMO and Ricoh GR master watermarks for every photo.
 *
 * The editor builds its watermark tabs from the photo's WatermarkInfo: hasselblad needs the device
 * block marked as a hasselblad photo, LUMO as a LUMO photo, GR needs the GR effect block
 * (gr.effect.info), and every tab is selectable only for photos with a device name, a supported
 * format and an OPPO / OnePlus / realme EXIF make (WatermarkMasterVM, "[isPhotoSupportWatermark]").
 * Each WatermarkInfo is built with a device block carrying both marks (the phone's market name
 * when the photo has none) and a GR block (all-zero GRInfo, as the photo details panel uses for a
 * photo without one); the selectable check answers true. A photo without EXIF make and model (a
 * screenshot) gets the phone's maker and market name in its WatermarkContent, which the watermark
 * text reads.
 */
public final class WatermarkSeriesUnlock {
    private static final String TAG = "WatermarkSeriesUnlock";
    private static final String GR_INFO = "com.oplus.gallery.foundation.codec.extend.GRInfo";

    private WatermarkSeriesUnlock() {}

    public static void installGallery(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "watermark info marks", () -> hookWatermarkInfo(symbols, classLoader));
        Log.guard(TAG, "watermark model text", () -> hookWatermarkContent(symbols));
        Log.guard(TAG, "watermark tabs selectable", () ->
            XposedBridge.hookMethod(symbols.method("MasterVM.photoSupports"), XC_MethodReplacement.returnConstant(Boolean.TRUE)));
    }

    private static void hookWatermarkInfo(Symbols symbols, ClassLoader classLoader) throws Throwable {
        Class<?> realmeInfo = symbols.cls("RealmeWatermarkInfo");
        Class<?> grInfo = XposedHelpers.findClass(GR_INFO, classLoader);
        Constructor<?> realmeWithGr = constructor(realmeInfo, grInfo);
        int hooked = 0;
        for (Constructor<?> constructor : symbols.cls("WatermarkInfo").getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            int realmeIndex = indexOf(types, realmeInfo);
            if (types.length == 0 || realmeIndex < 0) continue;
            Device device = Device.of(types[0]);
            if (device == null) continue;
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = device.marked(param.args[0]);
                    param.args[realmeIndex] = withGr(param.args[realmeIndex], realmeInfo, realmeWithGr, grInfo);
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new IllegalStateException("no WatermarkInfo constructor with device and realme blocks");
    }

    /** WatermarkContent(make, deviceName, location, time, shooting info, location array, address). */
    private static void hookWatermarkContent(Symbols symbols) {
        int hooked = 0;
        for (Constructor<?> constructor : symbols.cls("WatermarkContent").getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != 7 || types[0] != String.class || types[1] != String.class) continue;
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (blank(param.args[0])) param.args[0] = Build.MANUFACTURER;
                    if (blank(param.args[1])) param.args[1] = marketName();
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new IllegalStateException("no WatermarkContent constructor");
    }

    private static boolean blank(Object text) {
        return !(text instanceof String) || ((String) text).trim().isEmpty();
    }

    private static Object withGr(Object realme, Class<?> realmeInfo, Constructor<?> realmeWithGr, Class<?> grInfo) throws Throwable {
        Object[] parts = new Object[realmeWithGr.getParameterTypes().length];
        if (realme != null) {
            for (int i = 0; i < parts.length; i++) parts[i] = fieldOfType(realmeInfo, realmeWithGr.getParameterTypes()[i]).get(realme);
        }
        int grIndex = parts.length - 1;
        if (parts[grIndex] != null) return realme;
        parts[grIndex] = grInfo.getDeclaredConstructor().newInstance();
        return realmeWithGr.newInstance(parts);
    }

    /** The constructor that takes every field (the GR block last). */
    private static Constructor<?> constructor(Class<?> cls, Class<?> last) {
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length > 0 && types[types.length - 1] == last && types.length == instanceFields(cls)) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        throw new IllegalStateException("no full constructor in " + cls.getName());
    }

    private static int instanceFields(Class<?> cls) {
        int count = 0;
        for (Field field : cls.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) count++;
        return count;
    }

    private static Field fieldOfType(Class<?> cls, Class<?> type) {
        for (Field field : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("no " + type.getName() + " field in " + cls.getName());
    }

    private static int indexOf(Class<?>[] types, Class<?> type) {
        for (int i = 0; i < types.length; i++) if (types[i] == type) return i;
        return -1;
    }

    /** The device block (WatermarkDevice): name, sub-watermark type, hasselblad photo, LUMO photo. */
    private static final class Device {
        final Constructor<?> constructor;
        final Field name;
        final Field subType;

        private Device(Constructor<?> constructor, Field name, Field subType) {
            this.constructor = constructor;
            this.name = name;
            this.subType = subType;
        }

        static Device of(Class<?> cls) {
            try {
                Constructor<?> constructor = cls.getDeclaredConstructor(String.class, int.class, boolean.class, boolean.class);
                constructor.setAccessible(true);
                return new Device(constructor, fieldOfType(cls, String.class), fieldOfType(cls, int.class));
            } catch (Throwable error) {
                return null;
            }
        }

        Object marked(Object device) throws Throwable {
            String deviceName = device != null ? (String) name.get(device) : null;
            if (blank(deviceName)) deviceName = marketName();
            int type = device != null ? subType.getInt(device) : 0;
            return constructor.newInstance(deviceName, type, true, true);
        }
    }

    private static String marketName() {
        try {
            Object name = XposedHelpers.callStaticMethod(Class.forName("android.os.SystemProperties"), "get", "ro.vendor.oplus.market.name", "");
            if (name instanceof String && !((String) name).isEmpty()) return (String) name;
        } catch (Throwable ignored) {
        }
        return Build.MODEL;
    }
}
