package local.jiege.hook.gr;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.OptionalHook;
import local.jiege.hook.common.Symbols;

/**
 * GR port, gallery process (com.coloros.gallery3d): the GR watermark in the photo editor.
 *
 * The gallery has the GR watermark UI but gates it behind feature flags this device lacks, and
 * lacks the realme GR artwork. service.sh stages gr_style_*.json and the logos in
 * files/ricoh_gr_styles; these hooks open the gates and serve the artwork from there.
 * Photos taken in GR mode carry gr.effect.info; the editor offers the GR watermark for a photo
 * only when that block was read, which the gallery does for realme-branded watermarks only.
 * Obfuscated gallery classes and members are symbols (assets/symbols.json, "gallery").
 */
public final class GrGalleryWatermark {
    private static final String TAG = "RicohGrPort";
    private static final String STYLE_DIR = "/data/user/0/com.coloros.gallery3d/files/ricoh_gr_styles";
    private static final String STYLE_ASSET_PREFIX = "watermark_master_styles/";

    private GrGalleryWatermark() {}

    public static void install(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        hookFeatureGates(symbols);
        hookStyleSource(symbols);
        hookArtwork(classLoader);
        hookEditorSection(symbols);
        hookModelTextLabel(symbols);
        hookGrPhotoInfo(symbols);
        hookMasterFilterName(classLoader);
        Log.i(TAG, "gallery GR watermark hooks installed");
    }

    private static void hookFeatureGates(Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Config.getBoolean"),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object feature = param.args[0];
                        if ("feature_is_camera_gr_supported".equals(feature) || "feature_is_support_hassel_watermark".equals(feature)) {
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery GR feature gate hook failed", error);
        }
        try {
            // ai.master.support.double.select: needed for the camera -> gallery GR hand-off.
            XposedBridge.hookMethod(symbols.method("CameraInfo.doubleSelect"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gallery double-select hook failed", error);
        }
        // business_featureSwitch_is_camera_gr_supported and similar SharedPreferences switches.
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("android.app.SharedPreferencesImpl", null), "getBoolean",
            String.class, Boolean.TYPE, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object key = param.args[0];
                    if (key instanceof String && ((String) key).contains("is_camera_gr_supported")) param.setResult(Boolean.TRUE);
                }
            });
    }

    /** Built-in GR style assets are replaced by the staged files. */
    private static void hookStyleSource(final Symbols symbols) {
        try {
            final Class<?> assetSource = symbols.cls("AssetStyleSource");
            final Class<?> fileSource = symbols.cls("FileStyleSource");
            XposedBridge.hookMethod(symbols.method("StyleLoader.resolve"),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object source = param.args[0];
                            if (!assetSource.isInstance(source)) return;
                            String fileName = styleFileName((String) symbols.get("AssetStyleSource.name", source));
                            if (fileName == null) return;
                            File style = new File(STYLE_DIR, fileName);
                            if (!style.isFile()) {
                                Log.i(TAG, "gallery GR style missing " + style);
                                return;
                            }
                            param.setResult(XposedHelpers.newInstance(fileSource, style.getAbsolutePath()));
                        } catch (Throwable error) {
                            Log.e(TAG, "gallery GR style routing failed", error);
                        }
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery GR style routing setup failed", error);
        }
    }

    /** "watermark_master_styles/gr_style_N.json" -> "gr_style_N.json"; null for anything else. */
    private static String styleFileName(String asset) {
        if (asset == null || !asset.startsWith(STYLE_ASSET_PREFIX)) return null;
        String name = asset.substring(STYLE_ASSET_PREFIX.length());
        if (!name.startsWith("gr_style_") || !name.endsWith(".json") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null;
        return name;
    }

    /** realme GR logos and sketches, requested as drawables of the gallery, come from STYLE_DIR. */
    private static File artworkFile(Resources resources, int id) {
        if (resources == null || id == 0) return null;
        try {
            String name = resources.getResourceEntryName(id);
            if (name == null || !(name.startsWith("realme_gr") || name.startsWith("sketch_realme_gr")
                    || name.startsWith("sketch_camera_realme_gr") || name.startsWith("watermark_camera_realme_gr"))) return null;
            File file = new File(STYLE_DIR, name + ".webp");
            return file.isFile() ? file : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static void hookArtwork(ClassLoader classLoader) {
        try {
            XC_MethodHook rawResource = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    File file = artworkFile((Resources) param.thisObject, (Integer) param.args[0]);
                    if (file != null) param.setResult(new FileInputStream(file));
                }
            };
            OptionalHook.method(Resources.class, "openRawResource", rawResource, Integer.TYPE);
            OptionalHook.method(Resources.class, "openRawResource", rawResource, Integer.TYPE, TypedValue.class);

            XC_MethodHook drawable = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    setDrawable(param, (Resources) param.thisObject, (Integer) param.args[0]);
                }
            };
            OptionalHook.method(Resources.class, "getDrawable", drawable, Integer.TYPE, Resources.Theme.class);
            OptionalHook.method(Resources.class, "getDrawableForDensity", drawable, Integer.TYPE, Integer.TYPE, Resources.Theme.class);
            OptionalHook.method(XposedHelpers.findClassIfExists("androidx.core.content.res.ResourcesCompat", classLoader), "getDrawable",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        setDrawable(param, (Resources) param.args[0], (Integer) param.args[1]);
                    }
                }, Resources.class, Integer.TYPE, Resources.Theme.class);
            for (Method method : XposedHelpers.findClass("android.content.res.ResourcesImpl", null).getDeclaredMethods()) {
                if (!"loadDrawable".equals(method.getName())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Resources resources = null;
                        Integer id = null;
                        for (Object argument : param.args) {
                            if (argument instanceof Resources) resources = (Resources) argument;
                            else if (argument instanceof Integer && (Integer) argument > 0x7f000000) id = (Integer) argument;
                        }
                        if (resources != null && id != null) setDrawable(param, resources, id);
                    }
                });
            }
        } catch (Throwable error) {
            Log.e(TAG, "gallery GR drawable hooks failed", error);
        }
        try {
            XC_MethodHook decode = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    File file = artworkFile((Resources) param.args[0], (Integer) param.args[1]);
                    if (file == null) return;
                    BitmapFactory.Options options = param.args.length > 2 ? (BitmapFactory.Options) param.args[2] : null;
                    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                    if (bitmap != null) param.setResult(bitmap);
                }
            };
            XposedHelpers.findAndHookMethod(BitmapFactory.class, "decodeResource", Resources.class, Integer.TYPE,
                BitmapFactory.Options.class, decode);
            XposedHelpers.findAndHookMethod(BitmapFactory.class, "decodeResource", Resources.class, Integer.TYPE, decode);
        } catch (Throwable error) {
            Log.e(TAG, "gallery GR decodeResource hook failed", error);
        }
    }

    private static void setDrawable(XC_MethodHook.MethodHookParam param, Resources resources, int id) {
        File file = artworkFile(resources, id);
        if (file == null) return;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap != null) param.setResult(new BitmapDrawable(resources, bitmap));
    }

    /** WatermarkCameraSection: when opened from GR, show the GR style chips on top. */
    private static void hookEditorSection(final Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Section.cameraStyles"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (openedFromGr(symbols, param.thisObject)) param.setResult(Boolean.TRUE);
                }
            });
            XposedBridge.hookMethod(symbols.method("Section.onCreate"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!openedFromGr(symbols, self)) return;
                        symbols.call("Section.refreshStyles", self);
                        Object grChip = symbols.get("Section.grChip", self);
                        if (grChip != null) XposedHelpers.callMethod(grChip, "setChecked", true);
                        Object otherChip = symbols.get("Section.otherChip", self);
                        if (otherChip != null) XposedHelpers.callMethod(otherChip, "setChecked", false);
                        View root = (View) symbols.get("Section.root", self);
                        setChildVisibility(root, "camera_rm_chip_group", View.VISIBLE);
                        setChildVisibility(root, "camera_realme_gr_style_layout", View.VISIBLE);
                    } catch (Throwable error) {
                        Log.e(TAG, "WatermarkCameraSection GR setup failed", error);
                    }
                }
            });
            XposedBridge.hookMethod(symbols.method("Section.refreshStyles"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        View root = (View) symbols.get("Section.root", param.thisObject);
                        setChildVisibility(root, "camera_lumo_style_layout", View.GONE);
                        setChildVisibility(root, "camera_rm_chip_group", View.VISIBLE);
                        setChildVisibility(root, "camera_realme_gr_style_layout", View.VISIBLE);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "WatermarkCameraSection hooks failed", error);
        }
    }

    private static boolean openedFromGr(Symbols symbols, Object section) {
        if (section == null) return false;
        try {
            Context context = (Context) symbols.get("Editing.context", section);
            while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                context = ((ContextWrapper) context).getBaseContext();
            }
            Intent intent = context instanceof Activity ? ((Activity) context).getIntent() : null;
            return intent != null && "gr".equals(intent.getStringExtra("ai_master_watermark_mode_name"));
        } catch (Throwable error) {
            return false;
        }
    }

    private static void setChildVisibility(View root, String idName, int visibility) {
        if (root == null) return;
        int id = root.getResources().getIdentifier(idName, "id", root.getContext().getPackageName());
        View child = id > 0 ? root.findViewById(id) : null;
        if (child != null) child.setVisibility(visibility);
    }

    private static final ThreadLocal<Boolean> PARSING_GR_PHOTO = new ThreadLocal<>();

    /**
     * The watermark extension parser reads gr.effect.info (GRInfo) only when the photo's watermark
     * brand is realme; GR-mode photos here carry the OPPO brand. While a photo with that block is
     * parsed, the brand check answers true, so the editor gets GRInfo and offers the GR watermark.
     */
    private static void hookGrPhotoInfo(final Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Extend.parse"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object extensions = param.args[0];
                    if (extensions instanceof java.util.Map && ((java.util.Map<?, ?>) extensions).get("gr.effect.info") != null) {
                        PARSING_GR_PHOTO.set(Boolean.TRUE);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    PARSING_GR_PHOTO.remove();
                }
            });
            XposedBridge.hookMethod(symbols.method("Brand.isRealme"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (PARSING_GR_PHOTO.get() != null) param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gallery GR photo info hooks failed", error);
        }
    }

    /**
     * Rebuilding a master watermark names the filter by its index in the stock IPU list
     * (MasterModeInfo.getRealFilterName); module filters sit at other indices (ETERNA BB came back
     * as 人文). The name the camera stored with the photo (master.mode.preset.info) is used instead.
     */
    private static void hookMasterFilterName(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.gallery.foundation.codec.extend.mastermode.MasterModeInfo", classLoader,
                "getRealFilterName", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object stored = XposedHelpers.getObjectField(param.thisObject, "filterName");
                        if (stored instanceof String && !((String) stored).isEmpty()) param.setResult(stored);
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery master filter name hook failed", error);
        }
    }

    /** GR styles keep the "model" text source in the personalised watermark editor. */
    private static void hookModelTextLabel(final Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Personalized.setup"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String style = (String) symbols.get("Personalized.style", param.thisObject);
                        if (style == null || !style.startsWith("gr_style_")) return;
                        @SuppressWarnings("unchecked") List<Integer> labels = (List<Integer>) symbols.get("Personalized.labels", param.thisObject);
                        Context context = (Context) symbols.get("Editing.context", param.thisObject);
                        if (labels == null || context == null) return;
                        int model = context.getResources().getIdentifier("picture_editor_text_watermark_model", "string", context.getPackageName());
                        if (model != 0 && !labels.contains(model)) labels.add(0, model);
                    } catch (Throwable error) {
                        Log.e(TAG, "GR model text label failed", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "GR model text label setup failed", error);
        }
    }

}
