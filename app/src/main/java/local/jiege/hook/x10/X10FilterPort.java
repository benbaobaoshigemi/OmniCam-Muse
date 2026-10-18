package local.jiege.hook.x10;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Method;
import local.jiege.hook.filters.FilterCatalog;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.X10Filters;
import local.jiege.hook.common.ModeName;
import local.jiege.hook.common.Settings;
import local.jiege.settings.ModuleSettings;
import local.jiege.hook.common.Symbols;

/**
 * X10 port: the X10 filters 清透 (qing_tou.bin) and 琥珀 (hu_po.bin), and the X10 palette.
 *
 * - Filter lists: listed in both filter catalogs (local.jiege.hook.filters.X10Filters), which put
 *   them into the normal and master groups independently. This class gives them capture filter
 *   IDs 123/124 (plus the soft-light combinations), which are keyed by type and so shared.
 * - Master card: in master mode their card is laid out as a native card (fuji-proNegHi /
 *   ccd_base_warm) and then relabelled with the X10 names.
 * - Palette: seek-bar and palette colours for every filter type, and the palette capture
 *   algorithm (aps_algo_color_palette) is added to the capture algorithm list when the palette
 *   is on.
 * The LUTs, palette LMTs and APS configuration come from the KSU module payload.
 */
public final class X10FilterPort {
    private static final String TAG = "X10Filter";

    private X10FilterPort() {}
    private static final String[] TYPES = {X10Filters.QING_TOU, X10Filters.HU_PO};
    private static final Map<String, int[]> PALETTE = buildPalette();

    /** The camera app's class loader, captured at install. {@code CameraRequestTag} lives in the OCS
     *  library, so {@code tag.getClass().getClassLoader()} resolves the wrong loader for the
     *  camera-app-owned {@code Lut3dDataManager}. */
    private static volatile ClassLoader cameraLoader;

    public static void installCamera(ClassLoader classLoader) {
        cameraLoader = classLoader;
        ModeName.install(classLoader);
        hookFilterMap(classLoader);
        hookDrawing(classLoader);
        hookMasterCard(classLoader);
        hookPalette(classLoader);
        hookPaletteColors(classLoader);
        hookPaletteCapture(classLoader);
        hookHighPixelPaletteStamp(classLoader);
        hookStyleSwitchCount(classLoader);
        XposedBridge.log(TAG + ": loaded");
    }

    /**
     * FilterPresenter.styleCount() is the effect-style switch count. With filters added to the
     * groups it must follow the displayed style list (FilterPresenter.types()) instead of the
     * stock count.
     */
    private static void hookStyleSwitchCount(ClassLoader classLoader) {
        try {
            final Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("FilterPresenter.styleCount"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                    List<?> styles = (List<?>) symbols.call("FilterPresenter.types", x.thisObject);
                    x.setResult(styles.size());
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": style switch count hook failed " + t); }
    }

    private static Map<String, int[]> buildPalette() {
        Map<String, int[]> m = new HashMap<>();
        int[] none = colors("#312F2D", "#A2988F");
        put(m, none, "default", "Radiance.cube.rgb.bin", "vivid-warm.cube.rgb.bin", "vivid-lut.cube.rgb.bin", "mono.cube.rgb.bin", "mountains.cube.rgb.bin", "island.cube.rgb.bin", "city.cube.rgb.bin");
        put(m, colors("#848484", "#6591A0", "#8FB5C6"), "qing_tou.bin");
        put(m, colors("#746A64", "#967C7C", "#DC7B30"), "hu_po.bin");
        put(m, colors("#848484", "#3B9B6C", "#279AC4"), "800t.bin", "800t_master.bin", "neon-2020.cube.rgb.bin", "oplus_video_filter_neon");
        put(m, colors("#848484", "#6EC5B5", "#FFD239"), "fuji-eterna-v2.cube.rgb.bin");
        put(m, colors("#848484", "#4CA7C2"), "fuji_cc.bin", "fuji_cc_master.bin");
        put(m, colors("#848484", "#6CBC8A", "#3B66FB"), "natural.cube.rgb.bin");
        put(m, colors("#848484", "#D9AD92", "#FFA27D"), "portra400_normal.bin", "portra400_master.bin", "portra400_sdr_gen_a_1.bin,portra400_sdr_gen_d_1.bin", "portra400_hdr_normal_a_1.bin,portra400_hdr_normal_d_1.bin", "portra400_hdr_master_a_1.bin,portra400_hdr_master_d_1.bin");
        put(m, colors("#848484", "#957D5D", "#D7BF74"), "gourmet.cube.rgb.bin");
        put(m, colors("#848484", "#C69586", "#6B9BFF"), "fuji-proNegHi.bin", "fuji-proNegHi_master.bin");
        put(m, colors("#848484", "#2784B0", "#1EADF0"), "vivid-cool.cube.rgb.bin");
        put(m, colors("#848484", "#6E8C83", "#D4775A"), "fuji-nc.bin", "fuji-nc_master.bin");
        put(m, colors("#848484", "#967C7C", "#E69D55"), "ccd_base_warm.bin");
        put(m, colors("#848484", "#A1EBDD", "#95D6F3"), "ccd_base_cold.bin");
        put(m, colors("#848484", "#7A8C8D", "#93A4C0", "#D0B6B4"), "positive_normal.bin", "positive_master.bin", "positive_sdr_gen_a_1.bin,positive_sdr_gen_d_1.bin", "positive_hdr_normal_a_1.bin,positive_hdr_normal_d_1.bin", "positive_hdr_master_a_1.bin,positive_hdr_master_d_1.bin");
        put(m, colors("#848484", "#D2C1BA", "#FFC0BB"), "meicam.child.bin");
        put(m, colors("#141A18", "#6A6A5A", "#8E8E7C", "#F8FF95"), "Serenity.cube.rgb.bin");
        put(m, colors("#000000", "#A7A7A7"), "blackandwhite.cube.rgb.bin", "blackandwhite_HC_normal.bin", "blackandwhite_HC_master.bin", "blackandwhite_HC_sdr.bin", "blackandwhite_HC_hdr_normal.bin", "blackandwhite_HC_hdr_master.bin");
        return m;
    }

    private static int[] colors(String... values) {
        int[] result = new int[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Color.parseColor(values[i]);
        return result;
    }

    private static void put(Map<String, int[]> map, int[] recipe, String... types) {
        for (String type : types) map.put(type, recipe);
    }

    private static void hookPalette(ClassLoader classLoader) {
        try {
            Class<?> c = XposedHelpers.findClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColors", classLoader);
            Class<?> recipe = XposedHelpers.findClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColorRecipe", classLoader);
            XposedHelpers.findAndHookMethod(c, "get", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) {
                    String type = (String) x.args[0];
                    int[] value = PALETTE.get(type);
                    if (value == null) value = PALETTE.get("default");
                    x.setResult(XposedHelpers.newInstance(recipe, (Object) value.clone()));
                }
            });
            XposedHelpers.findAndHookMethod(c, "hasSeekbarColors", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) { x.setResult(Boolean.TRUE); }
            });
            XposedBridge.log(TAG + ": palette compatibility installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": palette hook failed " + t); }
    }

    private static void hookPaletteColors(ClassLoader classLoader) {
        try {
            Class<?> c = XposedHelpers.findClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors", classLoader);
            Class<?> recipe = XposedHelpers.findClass("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColorRecipe", classLoader);
            XposedHelpers.findAndHookMethod(c, "get", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) {
                    String type = (String) x.args[0];
                    Object value;
                    if (TYPES[0].equals(type)) {
                        value = XposedHelpers.newInstance(recipe,
                            (Object) new int[] {-6898736, 16777215},
                            (Object) new int[] {-1279595777, -1171735566});
                    } else if (TYPES[1].equals(type)) {
                        value = XposedHelpers.newInstance(recipe,
                            (Object) new int[] {16728642, 1694450242}, null,
                            (Object) new int[] {14449968, 1524399408, -2327248},
                            (Object) new float[] {0.0f, 0.43f, 1.0f});
                    } else {
                        value = XposedHelpers.newInstance(recipe,
                            (Object) new int[] {16777215, 16777215},
                            (Object) new int[] {1051431803, -1280601221});
                    }
                    x.setResult(value);
                }
            });
            XposedHelpers.findAndHookMethod(c, "hasPalette", String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) { x.setResult(Boolean.TRUE); }
            });
            XposedBridge.log(TAG + ": palette color compatibility installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": palette color hook failed " + t); }
    }

    private static void hookDrawing(ClassLoader classLoader) {
        try {
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", classLoader);
            XposedBridge.hookAllMethods(helper, "checkSpecialDrawingItem", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) {
                    if (x.args.length < 5 || !(x.args[3] instanceof Integer) || !(x.args[4] instanceof String)) return;
                    String type = (String) x.args[4];
                    int index = ((Integer) x.args[3]).intValue();
                    int filter = type.equals(TYPES[0]) ? 123 : type.equals(TYPES[1]) ? 124 : -1;
                    if (filter < 0) return;
                    Object model = x.args[1];
                    XposedHelpers.callMethod(model, "updateValue", Integer.valueOf(index), "drawing_item_filter_type", type);
                    XposedHelpers.callMethod(model, "setFilterIndex", type, Integer.valueOf(index));
                    XposedBridge.log(TAG + ": drawing mapped " + type + " id=" + filter);
                }
            });
        } catch (Throwable t) { XposedBridge.log(TAG + ": drawing hook failed " + t); }
    }

    /** Default filter's card in photo ("common"), portrait and (with its palette on) 哈苏超清 mode; it gets the same card as 清透/琥珀. */
    private static final String STANDARD = "default";
    /** The card's label gradient for "default": white to light grey. */
    private static final int[] WHITE_LABEL = {0xFFFFFFFF, 0xFFB9B9B9};

    /**
     * White label card text for a filter of the card's mode, from that mode's filter catalog
     * (normal or master), or null. The card keeps the title it was given.
     */
    private static String catalogLabel(Object model, String type) {
        String mode = ModeName.of(model);
        FilterEntry entry = FilterCatalog.get(FilterScope.ofMode(mode)).find(type);
        return entry == null ? null : entry.label;
    }

    /** Cards drawn in the X10 style: 清透, 琥珀, 标准 in photo, portrait and 哈苏超清 mode, registered label cards. */
    private static boolean isX10MasterCard(Object manager) throws Throwable {
        Object model = Symbols.get().get("Card.mode", manager);
        if (model == null) return false;
        String type = filterType(model);
        return TYPES[0].equals(type) || TYPES[1].equals(type) || catalogLabel(model, type) != null
                || (STANDARD.equals(type) && isStandardCardMode(model));
    }

    private static boolean isStandardCardMode(Object mode) {
        String name = ModeName.of(mode);
        return "common".equals(name) || "portrait".equals(name)
                || ("highPixel".equals(name) && Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS));
    }

    /** The filter type of a card's mode (ModeUi.filterType, implemented by each mode). */
    private static String filterType(Object model) throws Throwable {
        return (String) Symbols.get().call("ModeUi.filterType", model);
    }

    private static XC_MethodHook.Unhook hookNativeMasterCardType(Object manager) throws Throwable {
        final Symbols symbols = Symbols.get();
        Object model = symbols.get("Card.mode", manager);
        if (model == null) return null;
        final String type = filterType(model);
        // The card shows the label card only for its own film types; lay the card out as one of them.
        final String nativeType = TYPES[0].equals(type) || STANDARD.equals(type) || catalogLabel(model, type) != null ? "fuji-proNegHi.bin"
                : TYPES[1].equals(type) ? "ccd_base_warm.bin" : null;
        if (nativeType == null) return null;

        // The implementation the model's class runs (ModeUi.filterType is an interface method).
        final String typeName = symbols.name("ModeUi.filterType");
        Method typeMethod = null;
        for (Class<?> current = model.getClass(); current != null && typeMethod == null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (typeName.equals(method.getName()) && method.getParameterTypes().length == 0
                        && method.getReturnType() == String.class && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    typeMethod = method;
                    break;
                }
            }
        }
        if (typeMethod == null) throw new IllegalStateException("Missing " + model.getClass().getName() + "." + typeName + "()");
        final String cardClass = symbols.cls("Card").getName();
        final String filmCheck = symbols.name("Card.isFilmCard");
        final String layout = symbols.name("Card.layout");

        return XposedBridge.hookMethod(typeMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam x) {
                if (!type.equals(x.getResult())) return;
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (cardClass.equals(frame.getClassName())
                            && (filmCheck.equals(frame.getMethodName()) || layout.equals(frame.getMethodName()))) {
                        x.setResult(nativeType);
                        return;
                    }
                }
            }
        });
    }

    private static void releaseNativeMasterCardType(XC_MethodHook.MethodHookParam x) {
        XC_MethodHook.Unhook hook = (XC_MethodHook.Unhook) x.getObjectExtra("x10.master.native.type");
        if (hook != null) hook.unhook();
    }

    /**
     * The card's two text views: the title is the one inflated as effect_style_filter_hint, the
     * label the other one (created in code).
     */
    public static TextView[] cardTexts(Object manager) throws Throwable {
        TextView title = null, label = null;
        for (java.lang.reflect.Field field : Symbols.get().<java.lang.reflect.Field>all("Card.texts")) {
            TextView view = (TextView) field.get(manager);
            if (view == null) continue;
            boolean isTitle = false;
            try {
                isTitle = view.getId() > 0 && "effect_style_filter_hint".equals(view.getResources().getResourceEntryName(view.getId()));
            } catch (Resources.NotFoundException notNamed) {
                // a view without a resource name is the label
            }
            if (isTitle) title = view;
            else label = view;
        }
        return new TextView[] {title, label};
    }

    private static void restoreMasterCardLabels(Object manager) throws Throwable {
        TextView[] texts = cardTexts(manager);
        TextView title = texts[0];
        TextView label = texts[1];
        if (title == null || label == null) return;
        Resources resources = title.getResources();
        Object model = Symbols.get().get("Card.mode", manager);
        String type = filterType(model);
        String extraLabel = catalogLabel(model, type);
        int hintId = resources.getIdentifier("camera_filter_oplus_hint", "string", "com.oplus.camera");
        if (hintId == 0) throw new IllegalStateException("Missing native X10 filter label resources");
        if (!STANDARD.equals(type) && extraLabel == null) {  // 标准 and registered cards keep their title
            int titleId = resources.getIdentifier(TYPES[0].equals(type)
                    ? "camera_filter_oplus_qing_tou" : "camera_filter_oplus_hu_po", "string", "com.oplus.camera");
            if (titleId == 0) throw new IllegalStateException("Missing native X10 filter label resources");
            title.setText(titleId);
        }
        if (extraLabel != null) label.setText(extraLabel);
        else label.setText(hintId);
        // The borrowed film type brought its coloured label; 原生质感 uses the white "default" label.
        whiteLabel(label);
        Object labelContainer = Symbols.get().get("Card.labelBox", manager);
        if (labelContainer instanceof View) whiteLabel((View) labelContainer);

        fitCardTextWidths(title, label, labelContainer);
    }

    /** Expand the existing card to fit the final title and category label. */
    public static void fitCardTextWidths(Object manager) throws Throwable {
        TextView[] texts = cardTexts(manager);
        if (texts[0] == null || texts[1] == null) return;
        fitCardTextWidths(texts[0], texts[1], Symbols.get().get("Card.labelBox", manager));
    }

    private static void fitCardTextWidths(TextView title, TextView label, Object labelContainer) {
        int width = Math.max(
                (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + title.getPaddingLeft() + title.getPaddingRight(),
                (int) Math.ceil(label.getPaint().measureText(label.getText().toString())) + label.getPaddingLeft() + label.getPaddingRight());
        ViewGroup.LayoutParams titleParams = title.getLayoutParams();
        ViewGroup.LayoutParams labelParams = label.getLayoutParams();
        if (titleParams != null && titleParams.width >= 0) width = Math.max(width, titleParams.width);
        if (labelParams != null && labelParams.width >= 0) width = Math.max(width, labelParams.width);
        if (titleParams != null) { titleParams.width = width; title.setLayoutParams(titleParams); }
        if (labelParams != null) { labelParams.width = width; label.setLayoutParams(labelParams); }
        if (labelContainer instanceof View) {
            View view = (View) labelContainer;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) { params.width = width; view.setLayoutParams(params); }
        }
    }

    private static void whiteLabel(View view) {
        GradientDrawable white = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, WHITE_LABEL);
        if (view.getBackground() instanceof GradientDrawable) {
            float[] radii = ((GradientDrawable) view.getBackground()).getCornerRadii();
            if (radii != null) white.setCornerRadii(radii);
        }
        view.setBackground(white);
    }

    private static void hookMasterCard(ClassLoader classLoader) {
        try {
            Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("Card.isFilmCard"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                    if (isX10MasterCard(x.thisObject)) {
                        x.setObjectExtra("x10.master.native.type", hookNativeMasterCardType(x.thisObject));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam x) { releaseNativeMasterCardType(x); }
            });
            XposedBridge.hookMethod(symbols.method("Card.layout"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                    if (isX10MasterCard(x.thisObject)) {
                        x.setObjectExtra("x10.master.native.type", hookNativeMasterCardType(x.thisObject));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam x) throws Throwable {
                    releaseNativeMasterCardType(x);
                    if (isX10MasterCard(x.thisObject)) restoreMasterCardLabels(x.thisObject);
                }
            });
            XposedBridge.log(TAG + ": master card labels installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": master card labels failed " + t); }
    }

    private static void hookFilterMap(ClassLoader classLoader) {
        try {
            Class<?> helper = XposedHelpers.findClass("com.oplus.camera.filter.FilterHelper", classLoader);
            @SuppressWarnings("unchecked") Map<String, Integer> ids =
                (Map<String, Integer>) XposedHelpers.getStaticObjectField(helper, "sFilterPathConvertMap");
            putFilterId(ids, "qing_tou.bin", 123);
            putFilterId(ids, "hu_po.bin", 124);
            putFilterId(ids, "qing_tou.bin,morning.dream.cube.rgb.bin", 1096);
            putFilterId(ids, "hu_po.bin,morning.dream.cube.rgb.bin", 1097);
            putFilterId(ids, "qing_tou.bin,nostalgic.scene.cube.rgb.bin", 2096);
            putFilterId(ids, "hu_po.bin,nostalgic.scene.cube.rgb.bin", 2097);
            XposedBridge.log(TAG + ": capture filter enum map installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": capture filter enum map failed " + t); }
    }

    private static void putFilterId(Map<String, Integer> ids, String type, int id) {
        Integer previous = ids.get(type);
        if (previous != null && previous.intValue() != id) {
            throw new IllegalStateException(type + " already has enum " + previous);
        }
        ids.put(type, Integer.valueOf(id));
    }

    private static void hookPaletteCapture(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsProcessor", classLoader);
            Class<?> tagClass = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.CameraRequestTag", classLoader);
            Class<?> keys = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.ParameterKeys", classLoader);
            final Class<?> converter = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsDataConvert", classLoader);
            final Class<?> switches = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.apsAdapter.config.AlgoSwitchConfig", classLoader);
            final Object algorithmKey = XposedHelpers.getStaticObjectField(keys, "KEY_CAPTURE_ALGO_LIST");
            XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass,
                new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam x) {
                    if (x.hasThrowable()) return;
                    try {
                        Object tag = x.args[0];
                        boolean paletteEnable = XposedHelpers.getBooleanField(tag, "mbPaletteEnable");
                        if (!paletteEnable) {
                            // Diagnostic: high-pixel / professional are the modes where the palette
                            // preview works but the capture flag is never set, so log the two gates
                            // the stamp would hit.
                            Object modeRaw = XposedHelpers.getObjectField(tag, "mCaptureMode");
                            if (modeRaw instanceof String && stampsPalette((String) modeRaw)) {
                                try {
                                    Object apsTag = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                                    int cameraId = XposedHelpers.getIntField(tag, "mRearFrontCameraId");
                                    String mode = (String) XposedHelpers.callStaticMethod(converter, "getApsModeName",
                                        XposedHelpers.getObjectField(apsTag, "mModeName"), cameraId);
                                    boolean support = ((Boolean) XposedHelpers.callStaticMethod(switches,
                                        "getSupportCaptureAlgo", mode, cameraId, PaletteCaptureAlgorithms.PALETTE)).booleanValue();
                                    XposedBridge.log(TAG + ": palette gates mode=" + mode
                                        + " camera=" + cameraId + " mbPaletteEnable=false supportCaptureAlgo=" + support);
                                } catch (Throwable t) { XposedBridge.log(TAG + ": palette gate probe " + t); }
                            }
                            return;
                        }
                        Object apsTag = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                        int cameraId = XposedHelpers.getIntField(tag, "mRearFrontCameraId");
                        String mode = (String) XposedHelpers.callStaticMethod(converter, "getApsModeName",
                            XposedHelpers.getObjectField(apsTag, "mModeName"), cameraId);
                        if (!((Boolean) XposedHelpers.callStaticMethod(switches, "getSupportCaptureAlgo",
                                mode, cameraId, PaletteCaptureAlgorithms.PALETTE)).booleanValue()) return;
                        Object meta = x.getResult();
                        String[] original = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                        String[] updated = PaletteCaptureAlgorithms.include(original);
                        if (updated == original) return;
                        XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) updated);
                        XposedBridge.log(TAG + ": palette capture algorithm added, request="
                            + XposedHelpers.getIntField(tag, "mRequestId") + ", mode=" + mode + ", camera=" + cameraId);
                    } catch (Throwable t) { XposedBridge.log(TAG + ": palette capture update failed " + t); }
                }
            });
            XposedBridge.log(TAG + ": palette capture hook installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": palette capture hook install failed " + t); }
    }

    /**
     * The 哈苏超清 capture request is built with {@code mbPaletteEnable} unset, so
     * {@link #hookPaletteCapture} bails at its first gate and the palette never reaches the shot
     * even though the preview shows it and {@code getSupportCaptureAlgo(aiHighPixel, ...)} already
     * returns true. Photo mode sets the flag itself; this stamps it on for high-pixel, the same way
     * {@code HighPixelFilters.stampTag} stamps {@code mbFilterOpen}. Rides every ApsProcessor method
     * whose first parameter is a {@code CameraRequestTag}, so the flag is present from the moment
     * the SDK reads it (the algorithm list is chosen before {@code createMetaItemInfo} runs).
     *
     * The same stamp now also covers professional/master mode: its own "palette" is basic_tone (the
     * tone/saturation dial, MasterPalette), which bakes the look into the shot but never sets
     * mbPaletteEnable, so the shot leaves with no src.image and no palette stamp and the gallery's
     * 色彩风格 entry disappears. Stamping the flag here lets {@link #hookPaletteCapture} add
     * aps_algo_color_palette (now declared for "master" in the algo-switch table) so the native
     * backend writes src.image before basic_tone bakes the look - which is what the gallery's
     * color_palette re-render needs.
     */
    private static void hookHighPixelPaletteStamp(ClassLoader classLoader) {
        try {
            Class<?> processor = XposedHelpers.findClass("com.oplus.ocs.camera.consumer.ApsProcessor", classLoader);
            Class<?> tagClass = XposedHelpers.findClass("com.oplus.ocs.camera.common.util.CameraRequestTag", classLoader);
            int stamped = 0;
            for (java.lang.reflect.Method method : processor.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 0 || params[0] != tagClass) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        stampPalette(param.args[0]);
                    }
                });
                stamped++;
            }
            XposedBridge.log(TAG + ": palette stamp hooked on " + stamped + " request methods");
        } catch (Throwable t) { XposedBridge.log(TAG + ": palette stamp hook failed " + t); }
    }

    /** Modes whose capture request never sets {@code mbPaletteEnable} itself, so this hook stamps it. */
    private static boolean stampsPalette(String mode) {
        return "high_pixel_mode".equals(mode) || "professional_mode".equals(mode);
    }

    private static void stampPalette(Object tag) {
        if (tag == null) return;
        try {
            String mode = (String) XposedHelpers.getObjectField(tag, "mCaptureMode");
            if (!stampsPalette(mode)) return;
            // Only stamp when the user has actually moved the dial: the tone (w) and saturation (v)
            // fields of Lut3dDataManager stay off-centre after a drag, even though the W palette flag
            // is dropped the instant the shutter is tapped (the panel closes on that tap). Using W
            // here would always miss the shot; w/v survive the close.
            int[] wv = paletteFields(tag);
            if (wv == null) {
                XposedBridge.log(TAG + ": palette stamp: no lut manager, mode=" + mode);
                return;
            }
            if (wv[0] == 0 && wv[1] == 0) return;
            // This runs on every ApsProcessor request for the mode, so log only the false->true
            // transition and stamp the rest silently (setBooleanField is idempotent).
            if (!XposedHelpers.getBooleanField(tag, "mbPaletteEnable")) {
                XposedBridge.log(TAG + ": palette stamp: mode=" + mode + " w=" + wv[0] + " v=" + wv[1]);
                XposedHelpers.setBooleanField(tag, "mbPaletteEnable", true);
            }
        } catch (Throwable ignored) {
            // a tag without the field keeps its own value
        }
    }

    /** Returns [tone(w), saturation(v)] from the 3D-LUT manager, or null if it is absent. */
    private static int[] paletteFields(Object tag) {
        try {
            Class<?> holder = XposedHelpers.findClass(
                "com.oplus.camera.common.utils.Lut3dDataManager$b", cameraLoader);
            Object manager = XposedHelpers.getStaticObjectField(holder, "a");
            if (manager == null) return null;
            return new int[] { XposedHelpers.getIntField(manager, "w"), XposedHelpers.getIntField(manager, "v") };
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": high-pixel stamp: lut read failed " + error);
            return null;
        }
    }
}
