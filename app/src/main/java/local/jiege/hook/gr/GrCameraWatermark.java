package local.jiege.hook.gr;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;
import org.json.JSONObject;

/**
 * GR port: the GR "mode limit" watermark in the camera.
 *
 * The GR watermark styles (gr_style_1..5 JSON plus realme GR logos) are staged by service.sh in
 * files/mode_limit_watermark; the gallery keeps its own copy (see GrGalleryWatermark). In GR mode
 * the camera's master-watermark code is pointed at those styles, and the style picked in the
 * gallery editor is sent back by broadcast.
 */
final class GrCameraWatermark {
    private static final String TAG = "RicohGrPort";
    private static final String MODE_LIMIT_DIR = "/data/user/0/com.oplus.camera/files/mode_limit_watermark";
    private static final String GALLERY_STYLE_DIR = "/data/user/0/com.coloros.gallery3d/files/ricoh_gr_styles";
    // Local font directories: the camera's own downloads, then gallery fonts staged by service.sh.
    private static final String[] FONT_DIRS = {
        "/data/user/0/com.oplus.camera/files/video_watermark", "/data/user/0/com.oplus.camera/files/jiege/fonts",
    };
    private static final String STYLE_ACTION = "com.oplus.camera.ai.master.watermark.resource";
    private static final String DEFAULT_STYLE = "gr_style_1";
    private static final String GR_CAPTURE_SWITCH = "pref_watermark_gr_capture_switch_key";
    private static volatile Object grSwitchKey;
    private static final ThreadLocal<Boolean> readingCaptureWatermark = new ThreadLocal<>();
    private static volatile boolean styleReceiverRegistered;

    private GrCameraWatermark() {}

    // The 1.2.4 focal-length hook on the GR watermark info wrote decompiler alias fields that do not
    // exist on any build, so it only logged an error per capture; it is not carried over.
    static void install(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "GR watermark stored values", () -> hookStoredValues(symbols, classLoader));
        Log.guard(TAG, "GR watermark capture state", () -> hookCaptureState(symbols));
        Log.guard(TAG, "GR watermark partition", () -> hookWatermarkPartition(symbols));
        Log.guard(TAG, "GR watermark settings summary", () -> hookSettingsSummary(symbols, classLoader));
        hookEditorLaunch(symbols);
        hookStyleJson(symbols, classLoader);
        hookStyleRendering(symbols, classLoader);
        hookStyleReceiver(symbols, classLoader);
    }

    private static Object grSwitchKey(Symbols symbols) {
        Object key = grSwitchKey;
        if (key != null) return key;
        try {
            key = DataStore.key(symbols, "Keys.grWatermark", GR_CAPTURE_SWITCH);
            grSwitchKey = key;
        } catch (Throwable error) {
            Log.e(TAG, "GR watermark switch key missing", error);
        }
        return key;
    }

    /**
     * DataManager reads: in GR, the mode-limit watermark resolves to the GR style; outside GR, never.
     *
     * Turning the GR watermark off must stay off. The camera's own flow for the GR switch
     * (CameraManager.W1 on pref_watermark_gr_capture_switch_key, and the gallery's
     * GalleryConnectionService.sendResData2) stores open_state FALSE and clears the style id on
     * "off", so the GR style and open state are only supplied while that switch is not "off".
     */
    private static void hookStoredValues(final Symbols symbols, final ClassLoader classLoader) {
        XC_MethodHook read = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object key = param.args[0];
                if (key == null) return;
                if (Boolean.TRUE.equals(readingCaptureWatermark.get())
                        && Reflection.hasStringField(key, "pref_ai_master_watermark_photo_open_state")) {
                    param.setResult(grWatermarkOn(symbols, classLoader));
                    return;
                }
                if (Reflection.hasStringField(key, "pref_ai_master_watermark_mode_limit_style_id")) {
                    Object value = param.getResult();
                    boolean grStyle = value != null && String.valueOf(value).startsWith("gr_");
                    if (GrState.isGrMode()) {
                        param.setResult(grWatermarkOn(symbols, classLoader) ? (grStyle ? value : currentStyle()) : "");
                    } else if (grStyle) {
                        param.setResult(nonGrBackupStyle(symbols, classLoader));
                    }
                    return;
                }
                if (Reflection.hasStringField(key, "pref_watermark_mode_limit_name")) {
                    if (GrState.isGrMode()) param.setResult("gr");
                    else if ("gr".equals(param.getResult())) param.setResult("");
                    return;
                }
                if (Reflection.hasStringField(key, "pref_ai_master_watermark_mode_limit_open_state")) {
                    if (GrState.isGrMode()) param.setResult(grWatermarkOn(symbols, classLoader));
                } else if (Reflection.hasStringField(key, GR_CAPTURE_SWITCH)) {
                    Object value = param.getResult();
                    if ((value == null || "".equals(value)) && GrState.isGrMode()) param.setResult("on");
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.get"), read);
        XposedBridge.hookMethod(symbols.method("DataManager.getOr"), read);
    }

    /** Normal photo watermark settings. */
    private static final String[] NORMAL_WATERMARK_PREFS = {
        "pref_watermark_capture_switch_key",
        "pref_ai_master_watermark_photo_open_state",
        "pref_ai_master_watermark_photo_style_id",
        "pref_ai_master_watermark_photo_style_id_backup",
    };
    /** GR (mode-limit) watermark settings. */
    private static final String[] GR_WATERMARK_PREFS = {
        GR_CAPTURE_SWITCH,
        "pref_ai_master_watermark_mode_limit_open_state",
        "pref_ai_master_watermark_mode_limit_style_id",
        "pref_ai_master_watermark_mode_limit_style_id_backup",
    };

    /**
     * The GR and normal watermarks are stored apart: in GR mode only the GR settings are written,
     * in every other mode only the normal ones. The camera links the two in several places (the GR
     * switch handler in CameraManager, the gallery editor's style broadcast, which closes whichever
     * watermark it sends no style for); writes to the other side are dropped at the store.
     */
    private static void hookWatermarkPartition(Symbols symbols) {
        XC_MethodHook write = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object key = param.args[0];
                if (key == null) return;
                if (GrState.isGrMode() ? holdsAny(key, NORMAL_WATERMARK_PREFS) : holdsAny(key, GR_WATERMARK_PREFS)) {
                    param.setResult(null);
                }
            }
        };
        XposedBridge.hookMethod(symbols.method("DataManager.put"), write);
        XposedBridge.hookMethod(symbols.method("DataManager.putSync"), write);
    }

    private static boolean holdsAny(Object key, String[] names) throws IllegalAccessException {
        for (String name : names) {
            if (Reflection.hasStringField(key, name)) return true;
        }
        return false;
    }

    /** GR captures use the GR switch in the shared master-watermark decision. */
    private static void hookCaptureState(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("WatermarkModel.isOpen"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(Boolean) param.args[0] && (Boolean) param.args[1]
                        && ("gr".equals(param.args[2]) || GrState.isGrMode())) {
                    readingCaptureWatermark.set(Boolean.TRUE);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                readingCaptureWatermark.remove();
            }
        });
    }

    /**
     * The settings row says what the watermark page shows when it opens, which is its photo tab.
     * The camera's own summary is "on" when any of the photo, video or mode-limit (GR) watermarks
     * is open, so an open video or GR watermark shows as on over a closed photo watermark. In GR the
     * row follows the GR switch; elsewhere the photo watermark is on when its switch is "on" and it
     * has a style, which is what Util.openEditor hands the page.
     */
    private static void hookSettingsSummary(Symbols symbols, ClassLoader classLoader) {
        XposedBridge.hookMethod(symbols.method("Preference.assignment"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!Reflection.hasStringField(param.thisObject, "pref_watermark_setting_key")) return;
                Context context = (Context) Reflection.findFieldByType(param.thisObject, "android.content.Context");
                boolean on = GrState.isGrMode() ? grWatermarkOn(symbols, classLoader)
                    : watermarkOn(symbols, classLoader, "pref_watermark_capture_switch_key", "pref_ai_master_watermark_photo_style_id");
                String label = on ? "camera_setting_slogan_on" : "camera_setting_switch_off";
                int id = context.getResources().getIdentifier(label, "string", context.getPackageName());
                param.args[0] = context.getString(id);
            }
        });
    }

    private static boolean watermarkOn(Symbols symbols, ClassLoader classLoader, String switchPref, String stylePref) throws Throwable {
        Object switchValue = DataStore.get(symbols, classLoader, DataStore.key(symbols, "Keys.styleId", switchPref), "off");
        Object style = DataStore.get(symbols, classLoader, DataStore.key(symbols, "Keys.styleId", stylePref), "");
        return "on".equals(switchValue) && !"".equals(style);
    }

    /** The GR watermark switch as stored; unset reads as on (hookStoredValues defaults it to "on"). */
    private static boolean grWatermarkOn(Symbols symbols, ClassLoader classLoader) {
        Object key = grSwitchKey(symbols);
        if (key == null) return true;
        try {
            return !"off".equals(DataStore.get(symbols, classLoader, key, "on"));
        } catch (Throwable error) {
            Log.e(TAG, "GR watermark switch read failed", error);
            return true;
        }
    }

    private static Object nonGrBackupStyle(Symbols symbols, ClassLoader classLoader) {
        try {
            Object backupKey = DataStore.key(symbols, "Keys.styleId", "pref_ai_master_watermark_mode_limit_style_id_backup");
            Object backup = DataStore.get(symbols, classLoader, backupKey, "");
            if (backup != null && !String.valueOf(backup).startsWith("gr_")) return backup;
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String currentStyle() {
        String style = GrState.watermarkStyleId;
        return style != null ? style : DEFAULT_STYLE;
    }

    /** Opening the gallery editor from GR marks the request as a GR watermark edit. */
    private static void hookEditorLaunch(Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Util.openEditor"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (GrState.isGrMode()) param.args[1] = "gr";
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery editor mode hook failed", error);
        }
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof Intent) || !GrState.isGrMode()) return;
                Intent intent = (Intent) param.args[0];
                if (intent.getComponent() == null
                        || !"com.oplus.gallery.pictureeditorpage.PhotoEditorActivity".equals(intent.getComponent().getClassName())) return;
                intent.putExtra("ai_master_watermark_mode_name", "gr");
                intent.putExtra("is_camera_gr_supported", true);
            }
        });
    }

    /** WatermarkStyles.load loads a watermark style JSON; in GR, read the staged GR style from disk. */
    private static void hookStyleJson(final Symbols symbols, final ClassLoader classLoader) {
        try {
            XposedBridge.hookMethod(symbols.method("WatermarkStyles.load"),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (GrState.isGrMode()) param.args[0] = "gr";
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!GrState.isGrMode() && !"gr".equals(param.args[0])) return;
                        String style = styleFor(param.getResult(), symbols, classLoader);
                        File json = new File(MODE_LIMIT_DIR + "/" + style + "/" + style + ".json");
                        if (!json.isFile()) json = new File(MODE_LIMIT_DIR + "/" + style + ".json");
                        if (!json.isFile()) {
                            Log.i(TAG, "GR watermark style missing, keeping stock result: " + style);
                            return;
                        }
                        try {
                            JSONObject parsed = new JSONObject(readUtf8(json));
                            localizeFonts(parsed);
                            param.setResult(parsed);
                        } catch (Throwable error) {
                            Log.e(TAG, "GR watermark style read failed", error);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "GR watermark style hook failed", error);
        }
    }

    private static String styleFor(Object stockResult, Symbols symbols, ClassLoader classLoader) {
        if (stockResult instanceof JSONObject && ((JSONObject) stockResult).optString("styleId").startsWith("gr_")) {
            return ((JSONObject) stockResult).optString("styleId");
        }
        String style = currentStyle();
        try {
            Object stored = DataStore.get(symbols, classLoader,
                DataStore.key(symbols, "Keys.styleId", "pref_ai_master_watermark_mode_limit_style_id"), style);
            if (stored instanceof String && ((String) stored).startsWith("gr_")) style = (String) stored;
        } catch (Throwable ignored) {
        }
        return style;
    }

    /**
     * The GR styles name their fonts by URL (fontType 2, e.g. FZLTZCHK.zip for the model name). The
     * camera's MasterWatermarkElementProcessor.setTextPaint only resolves fontType 1 to a file and
     * bt.d.a feeds the string to Typeface.Builder, so a URL font silently falls back to the system
     * font. When the font is on the device (the camera's files/video_watermark, or the gallery's
     * watermark fonts staged in files/jiege/fonts), point the element at that file (fontType 0 = file path).
     */
    private static void localizeFonts(Object node) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.optInt("fontType", -1) == 2 && object.has("fontName")) {
                File font = localFont(object.optString("fontName"));
                if (font != null) {
                    object.put("font", font.getAbsolutePath());
                    object.put("fontType", 0);
                }
            }
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) localizeFonts(object.opt(keys.next()));
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) localizeFonts(array.opt(i));
        }
    }

    private static File localFont(String fontName) {
        String base = fontName.endsWith(".zip") ? fontName.substring(0, fontName.length() - 4) : fontName;
        for (String directory : FONT_DIRS) {
            for (String extension : new String[] {".TTF", ".ttf", ".OTF", ".otf"}) {
                File candidate = new File(directory, base + extension);
                if (candidate.isFile()) return candidate;
            }
        }
        return null;
    }

    private static String readUtf8(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        int read = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (read < bytes.length) {
                int count = input.read(bytes, read, bytes.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return new String(bytes, 0, read, "UTF-8");
    }

    /** The IPU master-watermark renderer: GR styles live in mode_limit_watermark and draw the GR logo. */
    private static void hookStyleRendering(Symbols symbols, ClassLoader classLoader) {
        try {
            XposedBridge.hookMethod(symbols.method("WatermarkModel.styleNormal"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (GrState.isGrMode()) param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "watermark style check hook failed", error);
        }
        String reader = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkJsonReader";
        String processor = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkElementProcessor";
        String customParams = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkCustomParams";
        try {
            XposedHelpers.findAndHookMethod(reader, classLoader, "getTargetDir", XposedHelpers.findClass(customParams, classLoader),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object params = param.args[0];
                        String style = params == null ? null : (String) XposedHelpers.callMethod(params, "getStyleId");
                        if (style != null && style.contains("gr")) param.setResult("/mode_limit_watermark");
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "getTargetDir hook failed", error);
        }
        try {
            XposedHelpers.findAndHookMethod(processor, classLoader, "isNotNeedDrawGrElement",
                XposedHelpers.findClass(customParams, classLoader), XC_MethodReplacement.returnConstant(Boolean.FALSE));
        } catch (Throwable error) {
            Log.e(TAG, "isNotNeedDrawGrElement hook failed", error);
        }
        try {
            Class<?> processorClass = XposedHelpers.findClass(processor, classLoader);
            XposedBridge.hookAllMethods(processorClass, "processImage", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 4 || !(param.args[3] instanceof ViewGroup) || !(param.args[1] instanceof JSONObject)) return;
                        JSONObject content = ((JSONObject) param.args[1]).optJSONObject("content");
                        String bitmap = content == null ? null : content.optString("bitmap");
                        if (bitmap == null || (!bitmap.startsWith("realme_gr") && !bitmap.startsWith("sketch"))) return;
                        ViewGroup group = (ViewGroup) param.args[3];
                        for (int i = 0; i < group.getChildCount(); i++) {
                            View child = group.getChildAt(i);
                            if (!(child instanceof ImageView) || ((ImageView) child).getDrawable() != null) continue;
                            Bitmap logo = decodeLogo(bitmap);
                            if (logo != null) ((ImageView) child).setImageBitmap(logo);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "GR watermark logo failed", error);
                    }
                }
            });
            XposedBridge.hookAllMethods(processorClass, "readImageFromProvider", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                    String name = (String) param.args[1];
                    if (!name.contains("realme_gr") && !name.contains("sketch")) return;
                    if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                    Bitmap logo = decodeLogo(name);
                    if (logo != null) param.setResult(logo);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "GR watermark renderer hooks failed", error);
        }
    }

    private static Bitmap decodeLogo(String name) {
        String file = name.endsWith(".webp") ? name : name + ".webp";
        for (String directory : new String[] {MODE_LIMIT_DIR, MODE_LIMIT_DIR + "/" + DEFAULT_STYLE, GALLERY_STYLE_DIR}) {
            File candidate = new File(directory, file);
            if (candidate.isFile()) return BitmapFactory.decodeFile(candidate.getAbsolutePath());
        }
        return null;
    }

    /** The gallery editor broadcasts the chosen GR style id back to the camera. */
    private static void hookStyleReceiver(final Symbols symbols, final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Application application = (Application) param.thisObject;
                if (styleReceiverRegistered || !"com.oplus.camera".equals(application.getPackageName())) return;
                application.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        String style = intent.getStringExtra("camera_watermark_rm_gr_photo_style_id");
                        if (style == null) style = intent.getStringExtra("styleId");
                        if (style == null || style.isEmpty()) return;
                        GrState.watermarkStyleId = style;
                        if (!GrState.isGrMode()) return;  // cached only; non-GR settings stay untouched
                        try {
                            DataStore.put(symbols, classLoader,
                                DataStore.key(symbols, "Keys.styleId", "pref_ai_master_watermark_mode_limit_style_id"), style);
                        } catch (Throwable error) {
                            Log.e(TAG, "GR watermark style store failed", error);
                        }
                    }
                }, new IntentFilter(STYLE_ACTION), Context.RECEIVER_EXPORTED);
                styleReceiverRegistered = true;
            }
        });
    }
}
