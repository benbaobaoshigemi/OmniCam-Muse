package local.jiege.hook.master;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.gr.GrEffectValues;
import local.jiege.hook.gr.GrPanel;
import local.jiege.hook.gr.GrPort;
import local.jiege.hook.highpixel.HighPixelFilters;

/**
 * Palette entry for master ("professional"), POP ("retroCamera") and video ("commonVideo") on PKJ110.
 *
 * <p>The stock filter menu only offers the palette in common/portrait/night: its gate is
 * {@code Lut3dDataManager.f(String mode)} = {@code CameraConfig.d("com.oplus.feature.color.palette.support")
 * && (mode is common|portrait|night)}. Master mode is "professional" and POP is "retroCamera", so
 * neither is on that list - it is a hard-coded mode list, answering the feature key (what
 * {@code PaletteFeatureSwitch} does for photo) changes nothing for them, and forcing {@code f()} open
 * makes the native palette backend run against those modes' filters and fogs the preview. The
 * built-in UI route is therefore a dead end for both.
 *
 * <p>This class goes the other way round: it never touches the stock gate or the APS configuration.
 * It hangs its own small button on the camera window, and when tapped instantiates the app's own
 * {@code PalettePanelView}, fills it with the recipe from {@code PaletteColors.get(filterType)}, and
 * then writes tone/saturation the same way the camera's own menu does. That write is small: the
 * app's callback for the two palette keys ({@code n9.f.P0} / {@code n9.f.O0}) only calls
 * {@code Lut3dDataManager.t}/{@code .z} with the value and notifies the mode object with
 * {@code H4}/{@code x6} - the settings store is used for the other keys (filter, soft light), not
 * for tone and saturation. So the panel writes through {@code ef.m$b} when the pro presenter is
 * alive, and straight into the LUT manager when it is not.
 *
 * <p>That fallback is what makes POP work from a cold start. The pro presenter {@code ef.m} is built
 * in exactly one place, the feature factory {@code gb.d0.c}, and only for the pro feature; a session
 * that boots into POP never creates one, so an entry point bound to it would only appear after a
 * detour through master mode. Tone and saturation do not need it - they live in the LUT manager's
 * {@code w} / {@code v} - so POP gets its own path and no longer depends on visit order.
 *
 * <p>Presenters are matched by class, not just by mode name: the camera keeps one per feature and
 * they all share the base class this module hooks ({@code kc.y} / {@code lc.w}), so POP alone brings
 * up a dozen of them, all reporting {@code retroCamera}. Only {@code ef.m} is accepted.
 *
 * <p>The entry point follows tone and saturation rather than the page. Master goes through its pro
 * presenter. Every page that has none - POP, and the video page, whose pro feature is never built -
 * is served straight from the LUT manager, since that is where the two values live. What differs
 * between the two is the arrangement: only master has room for the camera's lifted layout, the
 * others keep theirs and dock the dial in the strip under the shutter.
 *
 * <p>The video page needs one more thing before any of that shows. On the pages the palette ships
 * on, the preview renderer is handed the combined LUT and the viewfinder applies it; on the video
 * page the camera leaves that argument null, so the palette is computed and then dropped - the
 * values land, the native backend runs its whole palette path ("Palette process success!"), and the
 * picture does not move. The camera also refuses to initialise the environment there itself: its
 * renderer's own call returns without doing anything, flags or no flags. So the environment is
 * brought up directly and the preview task is handed the table, which is the same object the other
 * pages get - no camera gate is touched and the other pages are left alone.
 *
 * <p>That covers the viewfinder only, which is half of it: the clip is written by a backend the
 * app's rendering never reaches unless a video filter is in play. The record processor picks its
 * backend from its recorder config, and the default one hands MediaCodec to the HAL
 * ("vendor.qti-ext-enc-info-native_recording = 1"), so the encoder is fed from the sensor side and
 * nothing the app draws can end up in the file. The app-codec backend is fed from the app's own
 * pipeline, and the camera reaches for it by itself only when it has a filter to compute - which is
 * why a video page clip picks up the palette as soon as any filter is selected. While the dial has
 * been used on the video page the one config entry that picks the backend is answered here, so the
 * palette reaches the clip without a filter. See the recorder hook in {@link #install}.
 *
 * <p>In master the panel layout is arranged exactly like the camera's own palette: the control panel
 * is lifted by {@code dimen/palette_control_view_translation_4_3} (which is what moves the shutter
 * out of the way - the preview frame itself never moves), the mode strip, the zoom row and the
 * manual-parameter rows are hidden, the preview is masked into a rounded card, the app's own
 * 影调/饱和度 readout is inflated along the top edge, and the thumbnail is parked in the palette
 * row. POP has no room for that lift - its preview card ends at 1590 and its filter strip fills
 * 1590..1770, so raising the control panel would drive the shutter onto the strip - so there the
 * dial docks in the empty band under the shutter and only the mode strip steps aside. Every number
 * here was measured on the device with the layout snapshot in {@link #PROP_DUMP}.
 *
 * <p>The obfuscated names differ between camera builds: 7.006.94 (ROM 17.0.0.101/102) uses
 * {@code ef.m}/{@code rp.k0}/{@code em.p0}/{@code yg.a} and the methods {@code f0}/{@code R5}, while
 * 7.006.123X (ROM 17.0.0.104, the build this device runs) renames them to
 * {@code ff.n}/{@code tp.k0}/{@code fm.q0}/{@code zg.a} and {@code c0}/{@code Q5}. {@link #type}
 * and {@link #call} translate, and the newer build is detected by the presence of {@code ff.n}.
 *
 * <p>The feature is on by default: {@code persist.omnicam.aura.masterpalette=0} turns it off, and
 * with the property at 0 nothing is installed and the app behaves exactly as before.
 */
public final class MasterPalette {
    private static final String TAG = "MasterPalette";
    /** The name predates POP support; the switch covers both modes. Empty (unset) means on. */
    private static final String PROP = "persist.omnicam.aura.masterpalette";
    /**
     * The GR dial is switched off by default. It only opens with the camera's own GR menu and it has
     * to share that page with the style strip and the shutter, so it reads as noise for now; the code
     * is kept whole. Turn it on with {@code setprop persist.omnicam.aura.grpalette 1} and reopen the
     * camera.
     */
    private static final String GR_PALETTE_PROP = "persist.omnicam.aura.grpalette";
    /** Changing this property's value makes the camera print one layout snapshot (calibration). */
    private static final String PROP_DUMP = "persist.omnicam.aura.paldump";
    /** The stock photo-mode palette container; its appearance is the layout we want to copy. */
    private static final String[] PHOTO_PALETTE_IDS = {
        "palette_menu_root", "palette_panel_container", "palette_panel_view",
        "palette_reset_button_group", "palette_soft_light_button_group",
    };
    /**
     * Views the camera itself hides while its own (photo mode) palette is up - measured with the
     * paldump tree dump: the mode strip, the zoom row over the preview, the manual-parameter rows
     * and the whole top bar. Without hiding the first two they keep floating over the preview, and
     * without hiding the top bar its flash / aperture / sound-focus switches end up underneath the
     * 影调-饱和度-滤镜强度 readout we inflate at the same height, which makes both unreadable -
     * photo mode swaps the two, it does not show them together.
     */
    private static final String[] PALETTE_HIDE_IDS = {
        "headline_view", "headline_background",
        "zoom_seek_bar",
        "professional_params_bar_container", "professional_param_slide_bar_container",
        "oplus_setting_bar",
    };
    /**
     * POP and the video page only give up their mode strip and the top bar: the preview card and the
     * retro filter strip are the page, so everything else stays. The strip keeps working because the
     * click-catcher stops above it.
     */
    private static final String[] RETRO_HIDE_IDS = {
        "headline_view", "headline_background",
        "oplus_setting_bar",
    };
    /**
     * What the GR page gives up for the palette: the mode strip, whose band the palette block
     * uses, and the camera's own style row, which is a second copy of the strip over the preview.
     * The style strip itself stays - that is where a style is chosen, and the palette has no style
     * row of its own.
     */
    private static final String[] GR_HIDE_IDS = {
        "headline_view", "headline_background",
    };
    /** The strip over the preview the GR styles are chosen on. */
    private static final String GR_STYLE_LIST = "gr_filter_recyclerView";
    /**
     * The camera's GR menu container. It is what tells us the GR panel is on screen, independent of
     * the panel object, which can outlive the view it was built with.
     */
    private static final String GR_MENU_VIEW = "gr_effect_menu_layout";
    /** The camera's own scale bar inside its GR menu, the one control the palette covers. */
    private static final String GR_SCALE_BAR = "master_effect_menu_seekbar";
    /**
     * The camera's style row inside its GR menu - the one a tap on the 风格 entry brings up. It
     * shows the same styles as the strip over the preview, one row above it, so it is concealed for
     * as long as the camera's GR menu is on screen; the strip is the one that stays.
     */
    private static final String GR_STYLE_ROW = "gr_filter_select_recyclerView";
    /**
     * How far the GR control bar is raised while the palette is up. The block below it needs the
     * band the shutter row sits in, and the screen draws its navigation line at the bottom edge,
     * which the dial used to land on.
     */
    private static final int GR_PANEL_LIFT_DP = 20;
    /** Gap left between the bottom of the block and the screen's navigation line. */
    private static final int GR_BOTTOM_DP = 20;
    /** How far the camera lifts the control panel while its palette is up: dimen/palette_control_view_translation_4_3. */
    private static final int PALETTE_PANEL_LIFT_DP = 95;
    /** Dial top relative to the lifted control panel top (photo mode: 1836 - 1485 = 351px at 3x). */
    private static final int PALETTE_DIAL_TOP_DP = 117;
    /**
     * The preview card photo mode draws while its palette is up: the image is inset 14dp on both
     * sides (42px at 3x) and rounded with a 16dp radius (48px) - measured off the screenshots.
     */
    private static final int PREVIEW_CARD_INSET_DP = 14;
    private static final int PREVIEW_CARD_RADIUS_DP = 16;
    /**
     * The app's own 影调/饱和度 readout, shown along the top edge while its palette is up. Inflating
     * the layout keeps the labels (string/tone, string/camera_master_saturation), font and spacing
     * identical, and it takes the top bar's place rather than sharing it - see the hide lists.
     */
    private static final String READOUT_LAYOUT = "palette_value_panel";
    private static final String READOUT_LAYOUT_ALT = "palette_value_panel_multi_language";
    /**
     * The stock filter UI (filter strip / style switch). None of these are visible in master mode on
     * their own, so seeing one while the palette is up means the user opened the filter page: the
     * palette has to go, otherwise its click-catcher sits over the preview and the page can no longer
     * be dragged to switch filters.
     */
    private static final String[] FILTER_PANEL_IDS = {
        "effect_style_bottom_control_view", "effect_style_indicator", "effect_style_back_icon",
        "switch_filter_view", "style_filter_view",
    };
    /** Bottom-bar ids to try, in order, when measuring the free strip under the shutter. */
    private static final String[] CANDIDATE_CONTROL_IDS = {
        "bottom_bar", "control_panel_layout", "effect_style_bottom_control_view",
        "control_panel_button_layout", "bottom_control_bar",
    };
    /** Ids reported in the one-shot geometry line, to keep calibrating against the device. */
    private static final String[] GEOMETRY_IDS = {
        "control_panel_layout", "control_panel_button_layout", "shutter_button", "thumbnail",
        "headline_view", "zoom_seek_bar", "professional_params_bar_container",
        "palette_panel_view", "palette_menu_root", "frame_layout",
    };
    /** The retro-camera (POP) page root, used to tell us which mode we are looking at. */
    private static final String[] RETRO_UI_IDS = {
        "retro_camera_ui", "retro_camera_filter_scrollview",
    };
    /**
     * The camera's right-hand menu button in each mode; the palette button anchors above it. Looked
     * up by id first - the reflection walk in {@link #anchor} only sees buttons the effect container
     * keeps as fields, which is not true for every mode.
     */
    private static final String[] ANCHOR_IDS = {
        "camera_menu_right_enter_button", "camera_menu_right_enter_button_soft_light",
        "camera_menu_right_enter_button_retro_effect", "camera_menu_right_enter_button_filter",
    };

    private static ClassLoader loader;
    private static boolean camera123;
    private static boolean diagnostics;
    private static final List<WeakReference<Object>> presenters = new ArrayList<>();
    private static final List<WeakReference<Object>> containers = new ArrayList<>();
    /** Log-once guard for the calibration lines. */
    private static final Set<String> rendererSeen = new HashSet<>();
    /** The preview renderer, needed to hand its pipeline a combined LUT - see {@link #applyPreviewLut}. */
    private static volatile WeakReference<Object> rendererRef = new WeakReference<>(null);
    /**
     * The recorder-config key whose read decides how the clip is encoded. See {@link #install} -
     * the camera's video recorder picks its backend from this one entry, and only two of the
     * backends can carry the palette into the file.
     */
    private static Object appCodecKey;
    private static WeakReference<Session> session = new WeakReference<>(null);

    private MasterPalette() {}

    public static void install(ClassLoader classLoader) {
        loader = classLoader;
        if ("0".equals(readProp())) {
            Log.i(TAG, "off (" + PROP + "=0)");
            return;
        }
        // The layout snapshots are a calibration aid, not something to write into every log: they
        // run when the paldump property carries a value, and stay out of the way otherwise.
        String dump = prop(PROP_DUMP);
        diagnostics = !dump.isEmpty() && !"0".equals(dump);
        camera123 = XposedHelpers.findClassIfExists("ff.n", classLoader) != null;
        try {
            // Every presenter of this family derives from the abstract base (kc.y / lc.w) and gets
            // its mode name through that constructor, so hooking the base catches the ones the
            // feature factory builds for modes this module knows nothing about - POP among them.
            // ff.n itself is built in exactly one place (gb.d0.c) and only for the pro feature, so
            // the narrower hook below alone leaves a POP-only session with no presenter at all.
            try {
                XposedBridge.hookAllConstructors(cls("kc.y"), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        presenters.add(new WeakReference<>(param.thisObject));
                    }
                });
            } catch (Throwable error) {
                Log.i(TAG, "presenter base hook skipped " + error);
            }
            XposedBridge.hookAllConstructors(cls("ef.m"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    presenters.add(new WeakReference<>(param.thisObject));
                }
            });
            XposedBridge.hookAllConstructors(cls("rp.k0"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    containers.add(new WeakReference<>(param.thisObject));
                }
            });
            // Lut3dDataManager.f(mode) is the camera's own palette whitelist -
            // "com.oplus.feature.color.palette.support" && (common|portrait|night). Pages outside it
            // (POP, video) never get the palette branch of p() to run, so a write would land in the
            // LUT manager's fields and change nothing on screen. While our presenter-free palette is
            // docked we answer the gate ourselves; the moment it closes the camera has its own answer
            // back, and master - which does have a presenter and already passes the gate - is never
            // touched because the flag only follows the presenter-free path.
            try {
                XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.common.utils.Lut3dDataManager", loader, "f", String.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (directPaletteOpen) param.setResult(Boolean.TRUE);
                        }
                    });
            } catch (Throwable error) {
                Log.i(TAG, "palette gate hook skipped " + error);
            }
            // Calibration: record the pair the camera itself passes to the tone-environment
            // initialiser, so the fallback above can be checked against it.
            try {
                XposedHelpers.findAndHookMethod(
                    "com.oplus.camera.common.utils.Lut3dDataManager", loader, "e",
                    int.class, int.class, String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "camera tone env init " + param.args[0] + ","
                                + param.args[1] + " mode=" + param.args[2]);
                        }
                    });
            } catch (Throwable error) {
                Log.i(TAG, "tone env hook skipped " + error);
            }
            // The preview renderer only initialises the basic-tone environment when the camera tells
            // it to (nn.l.y0's first argument, taken from nn.l.l, which is derived from the mode).
            // The video page is told to skip it, and Lut3dDataManager.p() bails out on its very first
            // line when the environment is not up - so a palette write there changes fields and
            // nothing else. Asking for the environment on the video page is the whole fix: from then
            // on p() rebuilds the combined LUT exactly as it does in the modes that ship the palette.
            try {
                Class<?> renderer = cls("nn.l");
                if (renderer == null) throw new IllegalStateException("renderer class missing");
                XposedHelpers.findAndHookMethod(renderer, "y0", int.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // Only for the handle. The camera answers y0 by mode and returns without
                            // initialising anything on the pages it keeps the palette off, so the
                            // call cannot be talked into doing the work: measured, flipping its flags
                            // changed nothing. The manager has to be initialised directly (see
                            // ensureToneEnv), and the preview task has to be handed the LUT (see
                            // applyPreviewLut) - which needs this renderer.
                            rendererRef = new WeakReference<>(param.thisObject);
                            if (diagnostics && rendererSeen.add("req/" + param.args[0])) {
                                Log.i(TAG, "renderer env request mode="
                                    + rendererMode(param.thisObject) + " flag=" + param.args[0]);
                            }
                        }
                    });
            } catch (Throwable error) {
                Log.i(TAG, "renderer hook skipped " + error);
            }
            // The preview task carries the combined LUT as its fourth argument. The camera passes
            // null there on the pages it keeps the palette off - measured: the photo page builds it
            // as "flag1=true lut=786432", the video page as "flag1=false lut=null", same class, same
            // construction site, one empty argument - so the palette is computed and then dropped.
            // Handing the task the manager's own table is what puts the palette on the video
            // viewfinder; it runs only while our dial is up there, so no other page is touched.
            try {
                Class<?> task = cls("nn.f");
                if (task == null) throw new IllegalStateException("preview task class missing");
                XposedBridge.hookAllConstructors(task, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object[] args = param.args;
                        if (args.length < 4) return;
                        Object table = args[3];
                        if (diagnostics && rendererSeen.add("task/" + (table == null) + "/" + args[1])) {
                            Log.i(TAG, "preview task built flag1=" + args[1]
                                + " lut=" + (table instanceof float[]
                                    ? String.valueOf(((float[]) table).length) : String.valueOf(table)));
                        }
                        if (table != null || !wantPreviewLut()) return;
                        Object manager = lut();
                        if (manager == null) return;
                        float[] combined = combinedLut();
                        if (combined == null) return;
                        args[1] = Boolean.TRUE;
                        args[2] = XposedHelpers.getIntField(manager, "t");
                        args[3] = combined;
                        Log.i(TAG, "preview task given the combined LUT (" + combined.length + ")");
                    }
                });
            } catch (Throwable error) {
                Log.i(TAG, "preview task hook skipped " + error);
            }
            // The clip itself, as opposed to the viewfinder. Recording has several backends and
            // only one of them can carry our LUT into the file. `om.e0` (the camera's record
            // processor) builds its recorder config through `om.c0.a(om.z)` and then picks the
            // backend from three of those entries, in this order:
            //     is_use_fast_video_recorder (`om.e0$g.c`) -> 3
            //     is_use_media_codec         (`om.e0$g.b`) -> 2
            //     video_stream_media_codec   (`om.e0$g.V`) -> 4
            //     neither                                  -> 1
            // Backend 1 is the native recorder: MediaCodec is configured with
            // "vendor.qti-ext-enc-info-native_recording = 1", so the HAL feeds the encoder's input
            // surface itself and nothing the app renders can reach it. Backend 2 configures
            // MediaCodec with "mbUseCameraMediaCodec: true" instead, which means the encoder is fed
            // from the app's own pipeline - the very pipeline the palette is on. Measured on the
            // device: a clip recorded on backend 1 came out untoned (frame chroma 52.5 against the
            // 54.4 baseline while the dial sat at -100), while backend 2 followed the dial exactly
            // (dial at tone +100 / saturation -100: luma 100 -> 150, chroma 54 -> 24). The camera
            // only reaches for backend 2 by itself when it has a video filter to compute, which is
            // why selecting a filter makes the palette show up in the clip. While our dial has been
            // used on the video page we answer that one key ourselves, so the palette reaches the
            // file without a filter.
            //
            // It has to be this key and not the fast-video one: `om.e0.k` enters a whole block when
            // is_use_fast_video_recorder is set, and that block reads the speed-mode entries
            // (fast_video_speed_auto_mode) through a bare Boolean.booleanValue() with no null
            // check, because the camera only ever sets the flag together with those entries -
            // forcing it alone crashes on the shutter with
            // "NullPointerException ... at om.e0.k(RecordProcessor.java:227)". Raising
            // is_use_media_codec instead only makes the value more defined than it was, and the
            // backend-2 side effect (`om.l.g1 = true`) reads no further config.
            //
            // The switch is held back while the dial sits at a neutral 0/0, through wantPreviewLut:
            // the app backend costs more power than the native one and is not worth taking over a
            // clip that carries no change, so the camera keeps its own choice until there is
            // something to write into the file.
            try {
                Object appCodec = XposedHelpers.getStaticObjectField(cls("om.e0$g"), "b");
                if (appCodec == null || !"is_use_media_codec".contentEquals(
                        String.valueOf(field(appCodec, "a")))) {
                    throw new IllegalStateException("recorder key mismatch");
                }
                appCodecKey = appCodec;
                XposedBridge.hookAllMethods(cls("om.c0"), "a", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length != 1 || param.args[0] != appCodecKey) return;
                        if (!wantPreviewLut()) return;
                        if (Boolean.TRUE.equals(param.getResult())) return;
                        param.setResult(Boolean.TRUE);
                        Log.i(TAG, "recording asked for the app encoder pipeline");
                    }
                });
                Log.i(TAG, "video recorder backend hook installed");
            } catch (Throwable error) {
                Log.i(TAG, "video recorder hook skipped " + error);
            }
            // Tried and falsified 2026-10-16: answering "func_support_video_basic_tone" so that
            // BaseMode.H4/.x6 also write the vendor CaptureRequest keys (bb.s.e0 tone, bb.s.d0
            // saturation) beside the preview key. The hook did fire - the log shows
            // "answered func_support_video_basic_tone on com.oplus.camera.module.a" - and the
            // recording still came out untoned (frame chroma 52.5 against a 54.4 baseline while the
            // dial sat at tone/saturation -100). The video stream does not consume that channel on
            // this model, so the code is not kept. See port-x8u/README.md 4.19.
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!"com.oplus.camera.Camera".equals(activity.getClass().getName())) return;
                    Session current = session.get();
                    if (current != null && current.activity == activity) return;
                    if (current != null) current.close();
                    Session created = new Session(activity);
                    session = new WeakReference<>(created);
                    created.start();
                }
            });
            // Leaving the camera (e.g. tapping the thumbnail to open the gallery) must take the
            // palette down with it; otherwise it hangs over the photo viewer.
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Session current = session.get();
                    if (current != null && current.activity == param.thisObject) current.detach();
                }
            });
            // Anything the camera itself is about to do takes the palette down first. The palette
            // lifts the whole control panel while it is up, and a page built during that moment (the
            // filter page, above all) caches a touch area that stops short of the bottom half - so
            // the page can only be dragged from the top. Closing on the way into the click means the
            // camera builds its page against the untouched layout.
            XposedHelpers.findAndHookMethod(View.class, "performClick", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Session current = session.get();
                    if (current != null) current.onForeignClick((View) param.thisObject);
                }
            });
            Log.i(TAG, "installed independent button; original palette gates and APS configuration unchanged");
        } catch (Throwable error) {
            Log.e(TAG, "install failed", error);
        }
    }

    private static String readProp() {
        // Empty means "not set", which is the on state: only an explicit 0 turns the feature off.
        return prop(PROP);
    }

    private static volatile long lastEntryToggleAt;

    private static String prop(String name) {
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Object value = type.getMethod("get", String.class, String.class).invoke(null, name, "");
            return String.valueOf(value);
        } catch (Throwable error) {
            return "";
        }
    }

    /** True when the GR dial is turned on (default off, see {@link #GR_PALETTE_PROP}). */
    private static boolean grPaletteEnabled() {
        return "1".equals(prop(GR_PALETTE_PROP));
    }

    // --- reflection helpers ------------------------------------------------------------------

    private static Object stat(String className, String method, Object... args) {
        Class<?> target = cls(className);
        // 7.006.123X moved the SAU settings getter from com.oplusos.sau.common.utils.a#h to r1.d#l.
        if (camera123 && "com.oplusos.sau.common.utils.a".equals(className) && "h".equals(method)) {
            target = cls("r1.d");
            method = "l";
        }
        if (target == null) throw new IllegalStateException("class not found: " + className);
        // Older builds declare the trailing parameter as Integer, the newer one as Object; the JVM
        // lookup Xposed does is exact, so match by hand and fall back to Xposed only if nothing fits.
        for (Class<?> c = target; c != null; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (!candidate.getName().equals(method)) continue;
                if (candidate.getParameterCount() != args.length) continue;
                if (!matches(candidate.getParameterTypes(), args)) continue;
                try {
                    candidate.setAccessible(true);
                    return candidate.invoke(null, args);
                } catch (Throwable ignored) {
                }
            }
        }
        return XposedHelpers.callStaticMethod(target, method, args);
    }

    /** True when every argument can be passed where the matching parameter type expects it. */
    private static boolean matches(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) return false;
                continue;
            }
            Class<?> actual = arg.getClass();
            if (type.isAssignableFrom(actual)) continue;
            if (type == int.class && actual == Integer.class) continue;
            if (type == long.class && actual == Long.class) continue;
            if (type == boolean.class && actual == Boolean.class) continue;
            if (type == float.class && actual == Float.class) continue;
            return false;
        }
        return true;
    }

    private static Class<?> cls(String name) {
        return XposedHelpers.findClassIfExists(type(name), loader);
    }

    /**
     * Logical camera class name to this build's real name. 7.006.123X renamed every class the
     * palette path touches; the mapping is taken from the build where {@code ff.n} exists.
     */
    private static String type(String name) {
        if (!camera123) return name;
        switch (name) {
            case "ef.m": return "ff.n";
            case "ef.m$b": return "ff.n$b";
            case "kc.y": return "lc.w";
            case "kc.c0": return "lc.b0";
            case "rp.k0": return "tp.k0";
            case "com.oplus.camera.x6": return "com.oplus.camera.w6";
            case "em.p0": return "fm.q0";
            case "yg.a": return "zg.a";
            case "ap.d": return "bp.d";
            default: return name;
        }
    }

    private static Object call(Object target, String method, Object... args) {
        // 7.006.123X also renamed two methods on the presenter/container.
        if (camera123) {
            if ("f0".equals(method)) method = "c0";
            else if ("R5".equals(method)) method = "Q5";
        }
        return XposedHelpers.callMethod(target, method, args);
    }

    private static Object field(Object target, String name) {
        return XposedHelpers.getObjectField(target, name);
    }

    /** Value of the first declared field whose type name equals {@code type}, walking up. */
    private static Object byType(Object target, String type) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(type)) continue;
                try {
                    f.setAccessible(true);
                    return f.get(target);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The palette key constants live as static fields on n9.f (P0 = tone, O0 = saturation). */
    private static Object key(String name) {
        Class<?> type = cls("n9.f");
        return type == null ? null : XposedHelpers.getStaticObjectField(type, name);
    }

    /** The two modes this entry serves. POP reports "retroCamera"; master reports "professional". */
    private static final String MODE_MASTER = "professional";
    private static final String MODE_RETRO = "retroCamera";
    /** 哈苏超清: the camera builds no effect panel there, so the palette is served from the LUT manager. */
    private static final String MODE_HIGH_PIXEL = "highPixel";
    /** Bottom margin of the POP dial: POP keeps its preview and filter strip, so nothing is lifted. */
    private static final int RETRO_DIAL_BOTTOM_DP = 16;
    /**
     * Master presenter: an ef.m instance whose palette object (a yg.a field) is the em.p0 found
     * through rp.k0 -> com.oplus.camera.x6 -> f0 -> a -> a, and whose i() reports {@code mode}.
     *
     * <p>Only {@code ef.m} will do. The camera keeps one presenter per mode and they all share the
     * base class this module hooks, so a mode-string match on its own picks an arbitrary one of
     * them (POP alone brings up a dozen); the palette callback is {@code ef.m$b} and takes nothing
     * else, so any other class throws {@code NoSuchMethodError} on the constructor and the whole
     * open() dies. When no {@code ef.m} is alive - a session that never built the pro feature - the
     * caller falls back to the LUT manager, which needs no presenter at all.
     */
    private static Object palettePresenter(String mode) {
        Object registered = registeredPresenter(mode);
        return registered != null ? registered : presenterFromContainer(mode);
    }

    /** True when this is the pro presenter, the only one ef.m$b accepts (and so the only write path). */
    private static boolean isProPresenter(Object presenter) {
        Class<?> pro = cls("ef.m");
        return pro != null && pro.isInstance(presenter);
    }

    /**
     * Presenter reached through the effect container's own object graph, for the rare case where the
     * constructor hook missed the instance. Same restriction: it has to be the pro presenter.
     */
    private static Object presenterFromContainer(String mode) {
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) continue;
            for (Class<?> c = container.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Object value;
                    try {
                        f.setAccessible(true);
                        value = f.get(container);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (value == null || value.getClass().getName().startsWith("java.")) continue;
                    if (!isProPresenter(value)) continue;
                    if (byType(value, type("yg.a")) == null) continue;
                    try {
                        if (mode.equals(call(value, "i"))) return value;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Object registeredPresenter(String mode) {
        Object fallback = null;
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) {
                containers.remove(i);
                continue;
            }
            Object paletteObject = null;
            try {
                paletteObject = field(field(call(byType(container, type("com.oplus.camera.x6")), "f0"), "a"), "a");
            } catch (Throwable ignored) {
            }
            if (paletteObject != null && !type("em.p0").equals(paletteObject.getClass().getName())) {
                paletteObject = null;
            }
            for (int j = presenters.size() - 1; j >= 0; j--) {
                Object presenter = presenters.get(j).get();
                if (presenter == null) {
                    presenters.remove(j);
                    continue;
                }
                try {
                    if (!isProPresenter(presenter)) continue;
                    Object palette = byType(presenter, type("yg.a"));
                    if (palette == null || !mode.equals(call(presenter, "i"))) continue;
                    if (paletteObject != null && palette == paletteObject) return presenter;
                    if (fallback == null) fallback = presenter;
                } catch (Throwable ignored) {
                }
            }
        }
        return fallback;
    }

    /**
     * Diagnostic: who could carry palette writes in the mode we are in. Lists every presenter we
     * have seen with its mode string and palette object, then the same for the effect containers.
     * This is how the POP entry point is found - POP is a different presenter from master's.
     */
    private static void dumpModes(String why) {
        StringBuilder sb = new StringBuilder("MODES ").append(why)
            .append(" presenters=").append(presenters.size())
            .append(" containers=").append(containers.size()).append(" | ");
        int shown = 0;
        for (int i = presenters.size() - 1; i >= 0 && shown < 12; i--) {
            Object presenter = presenters.get(i).get();
            if (presenter == null) continue;
            shown++;
            Object palette = byType(presenter, type("yg.a"));
            sb.append(presenter.getClass().getName()).append(" mode=").append(modeOf(presenter))
                .append(" pal=").append(palette == null ? "null" : palette.getClass().getName())
                .append(" | ");
        }
        shown = 0;
        for (int i = containers.size() - 1; i >= 0 && shown < 6; i--) {
            Object container = containers.get(i).get();
            if (container == null) continue;
            shown++;
            sb.append("container ").append(container.getClass().getName()).append(" pal=");
            try {
                sb.append(field(field(call(byType(container, type("com.oplus.camera.x6")), "f0"), "a"), "a"));
            } catch (Throwable error) {
                sb.append("n/a");
            }
            sb.append(" | ");
        }
        for (int i = 0; i < sb.length(); i += 800) {
            Log.i(TAG, sb.substring(i, Math.min(sb.length(), i + 800)));
        }
    }

    /** The presenter's mode string ({@code i()}), or "?" when it cannot be read. */
    private static String modeOf(Object presenter) {
        try {
            return String.valueOf(call(presenter, "i"));
        } catch (Throwable error) {
            return "?";
        }
    }

    /**
     * The page the camera is on. Every presenter reports the current mode - that is what made the
     * pro presenter look like it was serving POP - so the first live one is enough, and it works
     * with or without a pro presenter. Returns "" when there is nothing to read yet.
     */
    private static String currentMode() {
        for (int i = presenters.size() - 1; i >= 0; i--) {
            Object presenter = presenters.get(i).get();
            if (presenter == null) {
                presenters.remove(i);
                continue;
            }
            String mode = modeOf(presenter);
            if (!mode.isEmpty() && !"?".equals(mode)) return mode;
        }
        return "";
    }

    /**
     * The mode of the page a preview renderer belongs to, read off the mode object the renderer
     * keeps ({@code nn.l.y.a.a}, a BaseMode whose {@code i()} is the mode name - the same call the
     * camera uses at this very site). It has to come from the renderer rather than from the list of
     * live presenters: this runs when the GL surface is created, which is before any presenter
     * exists, and an empty answer would silently skip the mode.
     */
    private static String rendererMode(Object renderer) {
        try {
            Object mode = field(field(field(renderer, "y"), "a"), "a");
            Object name = call(mode, "i");
            return name == null ? "" : String.valueOf(name);
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * Video pages, which the palette is extended to. The camera calls the plain video page
     * {@code commonVideo}; movie and the other video variants are matched by name so a rename does
     * not silently drop the mode from the list.
     */
    private static boolean isHighPixelMode(String mode) {
        return MODE_HIGH_PIXEL.equals(mode);
    }

    /**
     * Entry point for the camera's right-hand button on the 哈苏超清 page. The camera never builds its
     * effect panel there (CaptureParams.W3 is not called and both l2 views stay 0x0), so the button is
     * wired to this palette instead: same tone/saturation write path the POP and video pages use.
     */
    public static boolean toggleFromEntry() {
        // The camera's right-hand button reports its click twice for one tap on this page (its own
        // performClick plus the touch handling); without this the panel opened and shut again.
        long now = System.currentTimeMillis();
        if (now - lastEntryToggleAt < 600) return true;
        lastEntryToggleAt = now;
        Session current = session.get();
        if (current == null) {
            Log.i(TAG, "palette entry: no session yet");
            return false;
        }
        if (current.shelf != null) {
            current.hide();
            return true;
        }
        current.open();
        current.neutralize();
        return true;
    }

    private static boolean isVideoMode(String mode) {
        if (mode == null) return false;
        String lower = mode.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("video") || lower.contains("movie");
    }

    /** The right-hand menu button of the master UI, used both as anchor and as position source. */
    private static View anchor(Activity activity) {
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) {
                containers.remove(i);
                continue;
            }
            try {
                if (byType(container, "android.app.Activity") != activity) continue;
                View direct = (View) field(container, "o");
                if (direct != null && direct.isShown() && direct.getAlpha() > 0.1f) return direct;
                View fallback = null;
                for (Field f : container.getClass().getDeclaredFields()) {
                    if (!f.getType().getName().equals("com.oplus.camera.ui.effectcontainer.MenuRightButton")) {
                        continue;
                    }
                    f.setAccessible(true);
                    View candidate = (View) f.get(container);
                    if (candidate == null || !candidate.isShown() || candidate.getAlpha() <= 0.1f) continue;
                    int[] location = new int[2];
                    candidate.getLocationOnScreen(location);
                    if ((float) location[1] > activity.getResources().getDisplayMetrics().heightPixels * 0.40625f) {
                        fallback = candidate;
                    }
                }
                if (fallback != null) return fallback;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Reads a current palette value (P0 tone / O0 saturation) back from the presenter. */
    private static int value(Object presenter, String name) {
        if (presenter == null) return lutValue(name);
        Object result = stat("com.oplusos.sau.common.utils.a", "h",
            key(name), call(presenter, "M2"), Integer.class, 0);
        return ((Number) result).intValue();
    }

    // --- the presenter-free path -------------------------------------------------------------

    /**
     * The palette's own store. {@code Lut3dDataManager} is a plain singleton that keeps tone in
     * {@code w} and saturation in {@code v} and re-renders the combined LUT from {@code p()}. It is
     * the same class in both camera builds, and reading it back matches the tone/saturation the
     * panel reports.
     */
    private static Object lut() {
        Class<?> holder = cls("com.oplus.camera.common.utils.Lut3dDataManager$b");
        if (holder == null) return null;
        try {
            return XposedHelpers.getStaticObjectField(holder, "a");
        } catch (Throwable error) {
            return null;
        }
    }

    private static int lutValue(String name) {
        Object manager = lut();
        if (manager == null) return 0;
        try {
            return XposedHelpers.getIntField(manager, "P0".equals(name) ? "w" : "v");
        } catch (Throwable error) {
            return 0;
        }
    }

    /**
     * Pushes a palette value without a presenter. This is not a substitute for the write path the
     * camera uses - it <em>is</em> that write path: {@code ff.n$b.a} handles the two palette keys by
     * (1) calling {@code Lut3dDataManager.t} / {@code .z} with the value and {@code true} and
     * (2) notifying the mode object with {@code H4} / {@code x6}. The settings store
     * ({@code r1.d.K}) is only used for the other keys (filter, soft light), so tone and saturation
     * need nothing that a presenter holds.
     *
     * <p>This matters for POP: on a cold start straight into POP the feature factory never builds
     * {@code ff.n}, so there is no presenter to write through and the button used to disappear -
     * switching to master and back only worked because master had built one in the meantime.
     */
    private static void writeDirect(String name, int value) {
        ensureToneEnv();
        Object manager = lut();
        if (manager == null) return;
        forcePaletteFlag();
        try {
            XposedHelpers.callMethod(manager, "P0".equals(name) ? "t" : "z", value, true);
        } catch (Throwable error) {
            Log.e(TAG, "write " + name + " failed", error);
        }
    }

    /**
     * Brings the basic-tone environment up for the page the palette is on.
     *
     * <p>The camera only builds that environment from the preview renderer's surface callback, and
     * that callback does not run again when the user merely switches to another page, so on the pages
     * it is not built for the environment stays down and {@code p()} bails out on its very first line
     * - the written tone/saturation then changes fields and nothing else.
     */
    private static void ensureToneEnv() {
        Object manager = lut();
        if (manager == null) return;
        if (toneEnvUp(manager)) return;
        // Replaying the renderer's own call (nn.l.y0) does not help: the camera answers its own
        // initialiser by mode, and for the video page it returns without calling e() at all -
        // measured: "renderer env call returned flag=1 N=false". So the manager's own initialiser is
        // the only way in. The pair it takes is fixed per page (retroCamera gets 1,0), so try the
        // small values rather than guessing once.
        String mode = currentMode();
        if (mode.isEmpty()) mode = MODE_RETRO;
        for (int variant : new int[] {2, 1, 0}) {
            try {
                XposedHelpers.callMethod(manager, "l", false);
                Object result = XposedHelpers.callMethod(manager, "e", variant, 1, mode);
                Log.i(TAG, "tone env init variant=" + variant + " mode=" + mode
                    + " result=" + result + " " + lutState());
                if (toneEnvUp(manager)) return;
            } catch (Throwable error) {
                Log.i(TAG, "tone env init failed " + error);
                return;
            }
        }
    }

    /**
     * The combined 64-cube LUT the manager holds, once the environment is up - the same table the
     * camera's working pages hand to the preview task. Null while the environment is down, because
     * the table is only filled by the initialiser.
     */
    private static float[] combinedLut() {
        Object manager = lut();
        if (manager == null || !toneEnvUp(manager)) return null;
        try {
            Object table = XposedHelpers.getObjectField(manager, "c");
            if (table instanceof float[] && ((float[]) table).length > 0) return (float[]) table;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Hands the preview the combined LUT the way the camera's own palette pages do.
     *
     * <p>The camera builds its preview task (nn.f) with the table as its fourth argument, and on the
     * pages it keeps the palette off it passes null there instead: measured on the device, the photo
     * page builds it as "flag1=true lut=786432" while the video page builds it as
     * "flag1=false lut=null" - same class, same construction site, one empty argument. So the page
     * computes the palette and then drops it. Building the task ourselves with the manager's table
     * puts the palette on the viewfinder without touching any camera gate, and only while our dial is
     * up on such a page.
     */
    private static void applyPreviewLut() {
        if (!wantPreviewLut()) return;
        Object renderer = rendererRef.get();
        Object manager = lut();
        if (renderer == null || manager == null) return;
        float[] combined = combinedLut();
        if (combined == null) return;
        try {
            Object task = XposedHelpers.newInstance(cls("nn.f"),
                new Class<?>[] {renderer.getClass(), boolean.class, int.class, float[].class},
                renderer, Boolean.TRUE, XposedHelpers.getIntField(manager, "t"), combined);
            Object surface = XposedHelpers.getObjectField(renderer, "c");
            XposedHelpers.callMethod(surface, "K0", task, false);
            if (rendererSeen.add("posted")) {
                Log.i(TAG, "preview handed the combined LUT (" + combined.length + ") "
                    + "head=" + sample(combined));
            }
        } catch (Throwable error) {
            Log.i(TAG, "preview task re-post failed " + error);
        }
    }

    /**
     * True once the palette has been used on the video page during this visit, and kept for the rest
     * of the visit rather than only while the dial is on screen. Recording is what forces this: the
     * camera builds its preview program from the renderer's surface callback, starting a recording
     * adds a surface, and that rebuild asks for the combined LUT with a null table again - so the
     * palette was dropped at the exact moment it had to survive, because tapping the shutter is also
     * what closes our dial. Measured 2026-10-16: dial opened 07:22:12, "closed: camera UI tapped
     * (shutter_button)" 07:22:15, and the clip came out untoned while the preview had shown it.
     */
    private static volatile boolean videoPaletteWanted;

    /**
     * The first few entries of a table, for the log. Enough to tell an identity table from a graded
     * one: a real identity starts at 0,0,0, so anything that lifts that first triple is a wash over
     * the whole picture rather than a palette change.
     */
    private static String sample(float[] table) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(12, table.length); i++) {
            if (i > 0) out.append(',');
            out.append(table[i]);
        }
        return out.toString();
    }

    /**
     * The page-level half of the condition: the dial has been used on the video page during this
     * visit and the page is still a video page. It outlives the dial itself, because tapping the
     * shutter closes the dial and that is exactly the moment the palette has to still be there.
     */
    private static boolean videoPaletteLive() {
        return videoPaletteWanted && isVideoMode(currentMode());
    }

    /**
     * Whether the palette is live on the video page <em>and</em> actually doing something. Both the
     * preview hand-over and the recorder backend answer to this.
     *
     * <p>The neutral case is deliberately left out. A dial sitting at 0/0 is the identity table, and
     * handing it over is not free: this page's tone environment is one we have to bring up ourselves
     * (the camera never initialises it here), with a parameter pair it never uses - so a table that
     * is not exactly the identity shows on the viewfinder as a faint wash, and the app-codec recorder
     * costs more than the native one for a clip that carries no change. Leaving 0/0 alone keeps the
     * stock picture untouched until there is something to show.
     */
    private static boolean wantPreviewLut() {
        return videoPaletteLive() && paletteEngaged();
    }

    /** True while tone or saturation is off-centre, read from the LUT manager's own two fields. */
    private static boolean paletteEngaged() {
        Object manager = lut();
        if (manager == null) return true;
        try {
            return XposedHelpers.getIntField(manager, "w") != 0
                || XposedHelpers.getIntField(manager, "v") != 0;
        } catch (Throwable error) {
            return true;
        }
    }

    private static boolean toneEnvUp(Object manager) {
        try {
            return XposedHelpers.getBooleanField(manager, "N");
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * True while a presenter-free palette is docked, i.e. the LUT manager is the only write path.
     * The camera's own mode gate is answered for us while this holds - see {@link #install}.
     */
    private static volatile boolean directPaletteOpen;

    /**
     * {@code Lut3dDataManager.p()} only rebuilds the combined LUT when it is initialised (<b>N</b>),
     * the mode gate <b>f(P)</b> passes and the palette flag <b>W</b> is up; with W down it just logs
     * "paletteFlag: false" and leaves the LUT alone, so nothing on screen moves. Master has W raised
     * by its own mode object when it hands the palette over; a page the factory never built a pro
     * presenter for has nobody to do it, so we raise it ourselves while our dial is up.
     */
    private static void forcePaletteFlag() {
        Object manager = lut();
        if (manager == null) return;
        try {
            if (!XposedHelpers.getBooleanField(manager, "W")) {
                XposedHelpers.setBooleanField(manager, "W", true);
                Log.i(TAG, "raised palette flag (" + lutState() + ")");
            }
        } catch (Throwable ignored) {
        }
    }

    /** The three inputs {@code p()} checks, for the log - N initialised, W palette flag, P mode. */
    private static String lutState() {        Object manager = lut();
        if (manager == null) return "absent";
        try {
            return "N=" + XposedHelpers.getBooleanField(manager, "N")
                + " W=" + XposedHelpers.getBooleanField(manager, "W")
                + " P=" + XposedHelpers.getObjectField(manager, "P");
        } catch (Throwable error) {
            return "?";
        }
    }

    /**
     * The mode object behind the current page ({@code lc.w.a}), read off the effect container when no
     * presenter is available. Only used to notify it of a palette change; a failure here is harmless
     * because the value itself already went into the LUT manager.
     */
    private static Object modeObject() {
        Class<?> iface = cls("yg.a");
        for (int i = containers.size() - 1; i >= 0; i--) {
            Object container = containers.get(i).get();
            if (container == null) continue;
            try {
                // The effect container's own mode data is the mode itself - BaseMode implements the
                // same interface the palette callback talks to.
                Object mode = byType(container, type("com.oplus.camera.x6"));
                if (mode != null && iface != null && iface.isInstance(mode)) return mode;
                Object value = field(field(call(mode, "f0"), "a"), "a");
                if (value != null && iface != null && iface.isInstance(value)) return value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Simple name of whatever {@link #modeObject} found, for the log. */
    private static String describeClass(Object target) {
        return target == null ? "none" : target.getClass().getName();
    }

    /** Notifies the mode object of a palette change; the value itself is already in the manager. */
    private static void notifyMode(String name, int value) {
        Object target = modeObject();
        if (target == null) return;
        try {
            XposedHelpers.callMethod(target, "P0".equals(name) ? "H4" : "x6", value);
        } catch (Throwable ignored) {
        }
    }

    // --- the floating session ----------------------------------------------------------------

    private static final class Session {
        final Activity activity;
        final FrameLayout decor;
        final ImageView button;
        final Runnable tick;
        View shelf;
        View scrim;
        View controlBar;
        View previewMask;
        View thumb;
        float thumbBase;
        float thumbShift;
        Readout readout;
        final Map<View, Integer> hidden = new HashMap<>();
        Pending pending;
        boolean stopped;
        /** The camera's right-hand effect button, which is the palette's entry on the 哈苏超清 page. */
        int entryId;
        /** Whether the "closed by" line was already written for this session. */
        final Set<String> downLog = new HashSet<>();
        boolean photoPaletteOpen;
        boolean filterPanelOpen;
        boolean retroOpen;
        boolean grActive;
        boolean grSuppressed;
        /** The palette was opened from our own button rather than by the camera's GR menu. */
        boolean grManual;
        /** Log-once counter for the GR style row concealment. */
        int conceals;
        /** Last logged GR docking state, so only changes are reported. */
        String grState;
        long lastPopLog;
        String lastTrigger = "";
        String lastError = "";

        Session(Activity activity) {
            this.activity = activity;
            this.decor = (FrameLayout) activity.getWindow().getDecorView();
            this.entryId = activity.getResources().getIdentifier(
                "camera_menu_right_enter_button", "id", activity.getPackageName());
            this.button = new ImageView(activity);
            this.button.setContentDescription("调色板");
            int icon = activity.getResources().getIdentifier("filter_palette", "drawable", activity.getPackageName());
            if (icon != 0) this.button.setImageResource(icon);
            this.button.setPadding(dp(8), dp(8), dp(8), dp(8));
            this.button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0x87606060);
            background.setCornerRadius(dp(16));
            background.setStroke(dp(1), 0x44888888);
            this.button.setBackground(background);
            this.button.setVisibility(View.GONE);
            this.button.setElevation(dp(12));
            this.button.setOnClickListener(v -> {
                Log.i(TAG, "button click: grActive=" + grActive + " shelf=" + (shelf != null)
                    + " mode=" + currentMode());
                // The GR dial is opened from this button as well, and there the camera's own menu keeps
                // the take-over armed, so the second tap has to mean "put it away" rather than fall
                // through to open() again - which would rebuild the same dial and look like nothing
                // happened. Suppressing re-arms only once the camera's menu is gone (see hide()).
                if (grActive) {
                    Log.i(TAG, "gr palette: button closes");
                    closeGr(true);
                    return;
                }
                if (shelf != null) {
                    hide();
                } else {
                    open();
                }
            });
            this.tick = new Runnable() {
                @Override public void run() {
                    if (stopped || activity.isDestroyed()) {
                        close();
                        return;
                    }
                    try {
                        // Calibration: snapshot the layout the moment the stock photo-mode palette
                        // opens (that is the arrangement the master palette has to match), and any
                        // time the paldump property changes value so a state can be captured on
                        // demand over adb without rebuilding.
                        String trigger = prop(PROP_DUMP);
                        if (!trigger.equals(lastTrigger)) {
                            lastTrigger = trigger;
                            if (!trigger.isEmpty() && !"0".equals(trigger)) {
                                dumpModes("trigger-" + trigger);
                                postDump("trigger-" + trigger, true);
                            }
                        }
                        // The retro-camera (POP) page is a different page from master's; record who is
                        // around the moment it comes up. Calibration only, so it stays behind the
                        // paldump property like the other snapshots.
                        boolean retro = findByIds(RETRO_UI_IDS) != null;
                        if (retro != retroOpen) {
                            retroOpen = retro;
                            if (retro && diagnostics) {
                                dumpModes("retro-ui");
                                postDump("retro-ui", true);
                            }
                        }
                        boolean photoOpen = findByIds(PHOTO_PALETTE_IDS) != null;
                        if (photoOpen != photoPaletteOpen) {
                            photoPaletteOpen = photoOpen;
                            if (photoOpen) postDump("photo-palette");
                        }
                        View anchor = MasterPalette.anchor(activity);
                        Object presenter = MasterPalette.palettePresenter(
                            retro ? MODE_RETRO : MODE_MASTER);
                        View byId = findByIds(ANCHOR_IDS);
                        if (byId != null) anchor = byId;
                        if (diagnostics && retro && System.currentTimeMillis() - lastPopLog > 2000) {
                            lastPopLog = System.currentTimeMillis();
                            Log.i(TAG, "POPSTATE anchor=" + entryName(anchor)
                                + " presenter=" + (presenter == null ? "none" : presenter.getClass().getName())
                                + " lut=" + (lut() != null)
                                + " focus=" + decor.hasWindowFocus()
                                + " containers=" + containers.size()
                                + " presenters=" + presenters.size());
                        }
                        // Hide the little button while the palette is docked - except on the GR
                        // page when the dial was opened from it, where it is also the way back out.
                        boolean paletteUp = shelf != null && !(grActive && grManual);
                        // Three ways in, and the page decides which. Master has the pro presenter.
                        // POP and the video pages have none - the video page's pro feature is never
                        // built - and are served straight out of the LUT manager, since that is where
                        // tone and saturation live. The video page is recognised by name so plain
                        // photo, which has its own palette entry, is left alone.
                        boolean video = isVideoMode(currentMode());
                        // The GR page's parameters live in the camera's own style panel rows, so the
                        // parameter panel is laid over that panel while it is up rather than opened
                        // from a second entry point - the rows only exist while it does.
                        grTick();
                        // Leaving the video page ends the hand-over. Nothing has to be undone: the
                        // camera rebuilds its program for the page it moves to.
                        if (videoPaletteWanted && !video && !currentMode().isEmpty()) {
                            videoPaletteWanted = false;
                        }
                        // The GR palette stands on the GR rows rather than on a presenter, so the
                        // take-over itself is the write path there.
                        boolean canWrite = presenter != null || retro || grActive
                            || (isHighPixelMode(currentMode()) && lut() != null)
                            || (video && lut() != null);
                        if (anchor != null && canWrite && decor.hasWindowFocus() && !paletteUp) {
                            int[] anchorLoc = new int[2];
                            int[] decorLoc = new int[2];
                            anchor.getLocationOnScreen(anchorLoc);
                            decor.getLocationOnScreen(decorLoc);
                            int size = Math.max(dp(28), Math.min(dp(34), Math.min(anchor.getWidth(), anchor.getHeight())));
                            if (button.getWidth() != size) {
                                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
                                params.width = size;
                                params.height = size;
                                button.setLayoutParams(params);
                            }
                            button.setX(Math.max(dp(8), Math.min((float) (decor.getWidth() - size - dp(8)),
                                (anchorLoc[0] - decorLoc[0]) + (anchor.getWidth() - size) / 2f)));
                            button.setY(Math.max(dp(80), (anchorLoc[1] - decorLoc[1]) - size - dp(10)));
                            button.setVisibility(View.VISIBLE);
                            button.setEnabled(anchor.isEnabled());
                            button.bringToFront();
                        } else {
                            button.setVisibility(View.GONE);
                        }
                        // The camera lays the thumbnail out on its own schedule; hold it in the
                        // palette row for as long as the dial is up.
                        if (shelf != null && thumb != null) {
                            thumb.setTranslationY(thumbBase + thumbShift);
                        }
                        // Opening the filter page has to take the palette down: the click-catcher
                        // covers the preview, so the filter pager would never see the drag.
                        boolean filterOpen = findByIds(FILTER_PANEL_IDS) != null;
                        if (filterOpen != filterPanelOpen) {
                            filterPanelOpen = filterOpen;
                            if (filterOpen) postDump("filter-panel");
                        }
                        if (filterOpen && shelf != null && !grActive) {
                            Log.i(TAG, "closed: the camera's filter panel came up");
                            hide();
                        }
                        // Drop the palette only when there is no write path left at all (no presenter
                        // and no LUT manager). Window focus is not a signal here: the bar is a plain
                        // child view.
                        // The camera resets the palette flag whenever it rebuilds the tone
                        // environment, so hold it up for as long as the dial is docked.
                        if (directPaletteOpen) forcePaletteFlag();
                        // Leaving a supported page (switching mode, say) takes the palette down with
                        // it, and restores whatever the page had moved or hidden.
                        if (!canWrite && shelf != null) hide();
                    } catch (Throwable error) {
                        if (!error.toString().equals(lastError)) {
                            lastError = error.toString();
                            Log.e(TAG, "layout", error);
                        }
                    }
                    decor.postDelayed(this, 250);
                }
            };
        }

        void start() {
            decor.addView(button, new FrameLayout.LayoutParams(dp(34), dp(34)));
            decor.post(tick);
            Log.i(TAG, "camera UI attached");
        }

        void close() {
            stopped = true;
            decor.removeCallbacks(tick);
            hide();
            decor.removeView(button);
        }

        int dp(float value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        /**
         * The palette dial size photo mode uses (dimen/palette_color_size, 96dp on this build) so the
         * master dial comes out the same size instead of the oversized first version.
         */
        int dial() {
            int res = activity.getResources().getIdentifier("palette_color_size", "dimen",
                activity.getPackageName());
            int size = res == 0 ? 0 : activity.getResources().getDimensionPixelSize(res);
            return size > 0 ? size : dp(96);
        }

        /** First of {@code ids} that exists in the decor and is actually laid out. */
        View findByIds(String[] ids) {
            for (String id : ids) {
                int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
                if (res == 0) continue;
                View view = decor.findViewById(res);
                if (view != null && view.isShown() && view.getHeight() > 0) return view;
            }
            return null;
        }

        /** "id=[x,y,w,h]" in decor coordinates, or "id=n"/"id=?" when the view/id is missing. */
        String geometry(String id) {
            int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            if (res == 0) return id + "=?";
            View found = decor.findViewById(res);
            if (found == null) return id + "=n";
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            found.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return id + "=[" + (loc[0] - decorLoc[0]) + "," + (loc[1] - decorLoc[1]) + ","
                + found.getWidth() + "," + found.getHeight() + "]"
                + (found.isShown() ? "" : "hidden");
        }

        String entryName(View view) {
            if (view == null) return "none";
            try {
                return activity.getResources().getResourceEntryName(view.getId());
            } catch (Throwable error) {
                return "(id 0x" + Integer.toHexString(view.getId()) + ")";
            }
        }

        /** Snapshots the decor tree now and again once the camera's own animation has settled. */
        void postDump(String why) {
            postDump(why, diagnostics);
        }

        void postDump(String why, boolean enabled) {
            if (!enabled) return;
            decor.postDelayed(() -> dumpTree(why), 600);
            decor.postDelayed(() -> dumpTree(why + "+1s"), 1400);
        }

        private void dumpTree(String why) {
            if (stopped || activity.isDestroyed()) return;
            StringBuilder sb = new StringBuilder();
            int[] count = new int[1];
            collect(decor, 0, sb, count);
            Log.i(TAG, "TREE " + why + " decor=" + decor.getWidth() + "x" + decor.getHeight()
                + " views=" + count[0]);
            for (int i = 0; i < sb.length(); i += 800) {
                Log.i(TAG, "TREE|" + sb.substring(i, Math.min(sb.length(), i + 800)));
            }
        }

        /** Ids plus anything the camera itself moved or scaled - ids are what we compare across modes. */
        private void collect(View view, int depth, StringBuilder sb, int[] count) {
            if (view == null) return;
            boolean tagged = view.getId() != View.NO_ID;
            boolean moved = view.getTranslationX() != 0f || view.getTranslationY() != 0f
                || view.getScaleX() != 1f || view.getScaleY() != 1f;
            if ((tagged || moved) && view.isShown() && count[0] < 320) {
                count[0]++;
                int[] loc = new int[2];
                int[] decorLoc = new int[2];
                view.getLocationOnScreen(loc);
                decor.getLocationOnScreen(decorLoc);
                sb.append(tagged ? idOf(view) : "~").append(' ')
                    .append(view.getClass().getSimpleName()).append(' ')
                    .append(view.getVisibility() == View.VISIBLE ? "V"
                        : (view.getVisibility() == View.INVISIBLE ? "I" : "G"))
                    .append('[').append(loc[0] - decorLoc[0]).append(',').append(loc[1] - decorLoc[1])
                    .append(' ').append(view.getWidth()).append('x').append(view.getHeight()).append(']')
                    .append(" ty=").append(Math.round(view.getTranslationY()))
                    .append(" sy=").append(view.getScaleY())
                    .append(" a=").append(view.getAlpha())
                    .append(" | ");
            }
            if (view instanceof ViewGroup && depth < 14) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    collect(group.getChildAt(i), depth + 1, sb, count);
                }
            }
        }

        String idOf(View view) {
            if (view.getId() == View.NO_ID) return "~";
            try {
                return activity.getResources().getResourceEntryName(view.getId());
            } catch (Throwable error) {
                return "0x" + Integer.toHexString(view.getId());
            }
        }

        /** Removes the bar/scrim and puts the control bar back where the camera left it. */
        /**
         * 哈苏超清 starts from a neutral picture: the LUT manager still carries whatever the previous
         * page left in it, and the dial would otherwise open showing those numbers.
         */
        void neutralize() {
            if (pending == null) return;
            pending.tone = 0;
            pending.saturation = 0;
            pending.flush();
            Log.i(TAG, "palette starts neutral on this page");
        }

        void hide() {
            directPaletteOpen = false;
            if (shelf != null && !grActive && downLog.add("down")) {
                for (StackTraceElement frame : new Throwable().getStackTrace()) {
                    if (frame.getClassName().startsWith("local.jiege.")) continue;
                    Log.i(TAG, "palette down via " + frame.getClassName() + "." + frame.getMethodName());
                    break;
                }
            }
            // The GR palette borrows the camera's own panel for its rows: putting it away either
            // hands that panel back (the user closed the palette) or waits for the camera to build
            // it again, which is why the take-over stays armed off until the panel is torn down.
            if (grActive) {
                grActive = false;
                grManual = false;
                GrPanel.release();
                // The camera's GR menu is left where it is (its style list is what our column
                // clicks through), so the take-over only re-arms once that menu is put away -
                // otherwise closing the palette would immediately bring it straight back.
                grSuppressed = true;
                for (StackTraceElement frame : new Throwable().getStackTrace()) {
                    if (frame.getClassName().startsWith("local.jiege.hook.master")) continue;
                    Log.i(TAG, "gr palette down via " + frame.getClassName() + "."
                        + frame.getMethodName());
                    break;
                }
            }
            // The LUT hand-over deliberately does NOT stop here: closing the dial (which tapping the
            // shutter does) is not the same as giving the palette up, and on the video page the
            // values stay set until the page is left.
            if (pending != null) {
                pending.flush();
                pending = null;
            }
            if (shelf != null) {
                decor.removeView(shelf);
                shelf = null;
            }
            if (scrim != null) {
                decor.removeView(scrim);
                scrim = null;
            }
            if (controlBar != null) {
                controlBar.setTranslationY(0f);
                controlBar = null;
            }
            if (thumb != null) {
                thumb.setTranslationY(thumbBase);
                thumb = null;
            }
            if (previewMask != null) {
                decor.removeView(previewMask);
                previewMask = null;
            }
            if (readout != null) {
                decor.removeView(readout.view);
                readout = null;
            }
            restore();
        }

        /**
         * Inflates the app's own 影调/饱和度 readout and hangs it on the decor. The 柔光 group is
         * dropped because master mode's palette writes tone and saturation only.
         */
        Readout buildReadout() {
            int layout = activity.getResources().getIdentifier(READOUT_LAYOUT, "layout",
                activity.getPackageName());
            if (layout == 0) {
                layout = activity.getResources().getIdentifier(READOUT_LAYOUT_ALT, "layout",
                    activity.getPackageName());
            }
            if (layout == 0) return null;
            View view = activity.getLayoutInflater().inflate(layout, decor, false);
            View softLight = id(view, "palette_soft_light_group");
            if (softLight != null) softLight.setVisibility(View.GONE);
            // Nothing here is interactive, and it sits over the top bar: let taps fall through.
            disableTouch(view);
            decor.addView(view);
            return new Readout(view, text(view, "palette_tone_value"),
                text(view, "palette_saturation_value"));
        }

        void disableTouch(View view) {
            view.setClickable(false);
            view.setFocusable(false);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    disableTouch(group.getChildAt(i));
                }
            }
        }

        View id(View root, String name) {
            int res = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            return res == 0 ? null : root.findViewById(res);
        }

        TextView text(View root, String name) {
            View found = id(root, name);
            return found instanceof TextView ? (TextView) found : null;
        }

        /** Puts the views we borrowed (visibility) back the way the camera left them. */
        void restore() {
            for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
            hidden.clear();
        }

        /**
         * Puts one of the camera's views away, remembering what it was so {@link #hide()} can hand
         * it back. Called again on every tick while the palette is up: the camera re-lays its GR
         * rows out when the selected parameter changes and brings them back with it.
         */
        void hideView(String id) {
            View view = find(id);
            if (view == null || view.getVisibility() == View.GONE) return;
            if (!hidden.containsKey(view)) hidden.put(view, view.getVisibility());
            view.setVisibility(View.GONE);
        }

        int screenTop(View view) {
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            view.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return loc[1] - decorLoc[1];
        }

        int screenLeft(View view) {
            int[] loc = new int[2];
            int[] decorLoc = new int[2];
            view.getLocationOnScreen(loc);
            decor.getLocationOnScreen(decorLoc);
            return loc[0] - decorLoc[0];
        }

        /** A view by id, whether or not it is currently visible. */
        View find(String id) {
            int res = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            return res == 0 ? null : decor.findViewById(res);
        }

        /** Screen top of the first of these ids that is on screen, or 0 when none is. */
        int firstTop(String... ids) {
            for (String id : ids) {
                View view = find(id);
                if (view == null || !view.isShown() || view.getHeight() <= 0) continue;
                return screenTop(view);
            }
            return 0;
        }

        /** Camera paused (gallery, another app): take the palette down but keep the session. */
        void detach() {
            hide();
            button.setVisibility(View.GONE);
        }

        /**
         * A click landed on the camera's own UI. Take the palette down before the camera acts on it,
         * so whatever page it opens next is laid out against the untouched control panel. Clicks
         * inside our own button or dial are ignored.
         */
        void onForeignClick(View view) {
            // On the GR page the camera's own panel is both the style selector and the only thing
            // that owns the rows the GR palette drives, so taps there must not close it.
            if (grActive) return;
            if (shelf == null || view == null) return;
            // The camera performs clicks by itself while it hides its controls after a few idle
            // seconds; those land on views that are not on screen and used to take the palette down
            // with them. Only a click on something the user can actually see closes it.
            if (!view.isShown()) return;
            // On the 哈苏超清 page the camera's right-hand button is the palette's own entry, and the
            // click that opens the dial arrives here right after it, which closed it again.
            for (View node = view; node != null; ) {
                if (entryId != 0 && node.getId() == entryId) return;
                if (node == shelf || node == button) return;
                ViewParent parent = node.getParent();
                node = parent instanceof View ? (View) parent : null;
            }
            Log.i(TAG, "closed: camera UI tapped (" + idOf(view) + ")");
            hide();
        }

        /** Whether the camera is on the Ricoh GR page, whose parameters have their own panel. */
        static boolean isGrMode(String mode) {
            return "gr".equals(mode);
        }

        /**
         * Keeps the GR palette in step with the camera's own GR style panel. That panel is the only
         * thing that builds the rows the values live in, so the first time it comes up it is taken
         * over: hidden, and the palette is docked exactly where photo mode puts its own.
         */
        void grTick() {
            if (!isGrMode(currentMode())) {
                if (grActive) closeGr(false);
                grSuppressed = false;
                return;
            }
            // The stored style index is repaired here rather than while the app installs: the
            // settings store migrates itself out of SharedPreferences during startup, and touching
            // it then leaves the camera half-initialised. By the time this page is up it is ready.
            GrPort.repairStyleIndexOnce();
            View root = GrEffectValues.panelRoot();
            // The camera's own GR menu is the ground truth for "the panel is on screen". The panel
            // object the camera hands us can outlive the view it was built with, and then the
            // docking waits forever for a root that is never shown again - so the menu view counts
            // as well, and it is found by id every time.
            View menu = find(GR_MENU_VIEW);
            boolean menuUp = menu != null && menu.isShown() && menu.getHeight() > 0;
            boolean shown = menuUp || (root != null && root.isShown() && root.getHeight() > 0);
            logGrState(shown, root);
            // The camera's own style row comes up with its GR menu and again on every change of the
            // selected row; it is a second copy of the strip over the preview, so it is kept out of
            // the way for as long as that menu is on screen - with or without the palette.
            if (shown) concealStyleRow();
            if (grActive) {
                // The palette follows the camera's menu when that is what put it up, and stays put
                // when the user opened it from the button instead (there is no menu to follow then).
                if (!shown && !grManual) {
                    closeGr(false);
                    return;
                }
                if (shown) keepGrHidden();
                return;
            }
            if (grSuppressed) {
                if (!shown) grSuppressed = false;
                return;
            }
            if (grPaletteEnabled() && shown && GrEffectValues.available()) openGrPalette(root);
        }

        /**
         * Logs what the GR docking sees, once per change, so a menu that does not bring the palette
         * up can be told apart from a panel the camera never built.
         */
        void logGrState(boolean shown, View root) {
            View menu = find(GR_MENU_VIEW);
            String state = (shown ? "menu-up" : "menu-down")
                + (GrEffectValues.available() ? " bound" : " unbound")
                + (grActive ? " active" : "") + (grSuppressed ? " suppressed" : "")
                + (root == null ? " root=null"
                    : " root=" + idOf(root) + " " + root.getWidth() + "x" + root.getHeight()
                        + " v=" + root.getVisibility())
                + (menu == null ? " no-menu-view"
                    : " menuView=" + menu.getWidth() + "x" + menu.getHeight() + " v="
                        + menu.getVisibility() + " shown=" + menu.isShown());
            if (state.equals(grState)) return;
            grState = state;
            Log.i(TAG, "gr state: " + state);
        }

        /**
         * Re-applies the GR take-over while the palette is up: the mode strip is what the block
         * needs out of the way, and the camera lays its own views out again on its own terms.
         */
        void keepGrHidden() {
            for (String id : GR_HIDE_IDS) hideView(id);
        }

        /**
         * Conceals the camera's own style row - the second row of style icons inside its GR menu.
         * Concealed rather than hidden: a RecyclerView taken out with GONE is laid out again from
         * scratch and loses the children the camera keeps its own state in. Deliberately not part
         * of the hidden map: the row belongs to the camera's menu, which puts it away by itself,
         * and this is re-applied on every tick while that menu is up.
         */
        void concealStyleRow() {
            View row = find(GR_STYLE_ROW);
            if (row == null || row.getVisibility() != View.VISIBLE) return;
            row.setVisibility(View.INVISIBLE);
            if (conceals++ < 3) Log.i(TAG, "gr style row concealed (camera menu up)");
        }

        /**
         * Diagnostic aid for the GR page: every view the GR menu is built from, with where it ended
         * up, so a strip that is not where it is expected can be told apart from one that is.
         */
        void logGrViews() {
            List<View> all = new ArrayList<>();
            collectAll(decor, all, 0);
            StringBuilder line = new StringBuilder("GR views:");
            for (View view : all) {
                String name = idOf(view);
                if (!name.startsWith("gr") && !name.contains("filter")
                    && !name.contains("soft_light")) {
                    continue;
                }
                line.append(' ').append(name).append('[').append(view.getWidth()).append('x')
                    .append(view.getHeight()).append(" v=").append(view.getVisibility())
                    .append(" ty=").append(Math.round(view.getTranslationY()))
                    .append(" y=").append(screenTop(view)).append(']');
            }
            Log.i(TAG, line.toString());
        }

        void collectAll(View view, List<View> out, int depth) {
            out.add(view);
            if (depth > 20 || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectAll(group.getChildAt(i), out, depth + 1);
            }
        }

        /**
         * Docks the GR palette under the camera's own GR UI. Its style strip is where a style is
         * chosen, so it stays exactly where it is; the block is laid out below both it and the
         * shutter, and the shutter row is raised first so that band reaches the bottom edge of the
         * screen without the dial landing on the navigation line.
         */
        void openGrPalette(View cameraRoot) {
            try {
                controlBar = findByIds(CANDIDATE_CONTROL_IDS);
                if (controlBar != null) controlBar.setTranslationY(0f);
                restore();
                for (String id : GR_HIDE_IDS) hideView(id);
                concealStyleRow();
                if (controlBar != null) controlBar.setTranslationY(-dp(GR_PANEL_LIFT_DP));

                View strip = find(GR_STYLE_LIST);
                int stripTop = strip != null && strip.getHeight() > 0 ? screenTop(strip) : 0;
                int stripBottom = strip != null && strip.getHeight() > 0
                    ? screenTop(strip) + strip.getHeight() : 0;
                View shutter = find("shutter_button");
                int shutterBottom = shutter != null && shutter.getHeight() > 0
                    ? screenTop(shutter) + shutter.getHeight() : 0;
                // Two placements, matching the two block layouts in GrPanel: the ring is centred on the
                // preview (it covers the picture, which is why it is off right now), the strip is
                // docked under the shutter where the camera's own GR UI leaves room for it.
                View frame = find("frame_layout");
                GrPanel.prepare();
                int dialPx;
                int rowTop;
                int rowHeight;
                int dialTop;
                int width;
                int leftMargin;
                boolean ring = GrPanel.ringEnabled();
                if (ring) {
                    int frameTop = frame != null && frame.getHeight() > 0 ? screenTop(frame) : dp(120);
                    int frameLeft = frame != null && frame.getWidth() > 0 ? screenLeft(frame) : 0;
                    int frameWidth = frame != null && frame.getWidth() > 0
                        ? frame.getWidth() : decor.getWidth();
                    int bandTop = frameTop + dp(8);
                    int bandBottom = (stripTop > bandTop ? stripTop : frameTop + frameWidth) - dp(8);
                    int bandHeight = Math.max(dp(200), bandBottom - bandTop);
                    int limit = Math.min(bandHeight, frameWidth);
                    // The chips hang off the dial, so the dial is shrunk until the whole ring fits.
                    dialPx = Math.min(Math.round(dial() * 0.66f), Math.round(bandHeight * 0.55f));
                    int side = GrPanel.side(activity, dialPx);
                    while (side > limit && dialPx > dp(64)) {
                        dialPx -= dp(8);
                        side = GrPanel.side(activity, dialPx);
                    }
                    rowTop = bandTop + (bandHeight - side) / 2;
                    rowHeight = side;
                    dialTop = rowTop + side / 2 - dialPx / 2;
                    width = side;
                    leftMargin = frameLeft + (frameWidth - side) / 2;
                } else {
                    int rowStart = Math.max(stripBottom, shutterBottom) + dp(8);
                    int rowsPx = GrPanel.height(activity, 0);
                    int room = decor.getHeight() - dp(GR_BOTTOM_DP) - rowStart - rowsPx;
                    dialPx = Math.max(dp(56), Math.min(Math.round(dial() * 0.66f), room));
                    rowHeight = rowsPx + dialPx;
                    int bottomLimit = decor.getHeight() - dp(GR_BOTTOM_DP);
                    rowTop = rowStart;
                    if (rowTop + rowHeight > bottomLimit) {
                        rowTop = Math.max(dp(8), bottomLimit - rowHeight);
                    }
                    dialTop = rowTop + rowsPx;
                    width = ViewGroup.LayoutParams.MATCH_PARENT;
                    leftMargin = dp(8);
                }

                View row = GrPanel.build(activity, dialPx, grStyles());
                if (row == null) return;

                if (frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
                    FrameLayout.LayoutParams maskParams =
                        new FrameLayout.LayoutParams(frame.getWidth(), frame.getHeight());
                    maskParams.leftMargin = screenLeft(frame);
                    maskParams.topMargin = screenTop(frame);
                    previewMask = new PreviewCardMask(activity,
                        dp(PREVIEW_CARD_INSET_DP), dp(PREVIEW_CARD_RADIUS_DP));
                    decor.addView(previewMask, maskParams);
                }
                // The thumbnail is the camera's, and with nothing lifted it stays in its own corner.
                thumb = null;

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, rowHeight, Gravity.TOP);
                params.leftMargin = leftMargin;
                if (!ring) params.rightMargin = dp(8);
                params.topMargin = rowTop;
                decor.addView(row, params);
                shelf = row;
                decor.bringChildToFront(row);
                row.post(() -> Log.i(TAG, "gr row at " + screenTop(row) + " "
                    + row.getWidth() + "x" + row.getHeight()));
                logGrViews();

                // The camera's own menu keeps owning the picture: a tap on it is what closes that
                // menu, and the palette then follows it down (see grTick). So only the camera's own
                // scale bar is covered - a tap on the bar moves the parameter of whatever row it
                // last selected, which is the row one of the dial's axes is driving.
                View bar = find(GR_SCALE_BAR);
                int barTop = bar != null && bar.getHeight() > 0 ? screenTop(bar) : 0;
                int barBottom = bar != null && bar.getHeight() > 0
                    ? barTop + bar.getHeight() : 0;
                if (barTop > dp(1) && barBottom > barTop) {
                    View scrimView = new View(activity);
                    scrimView.setBackgroundColor(0x01000000);
                    scrimView.setClickable(true);
                    FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, barBottom - barTop, Gravity.TOP);
                    barParams.topMargin = barTop;
                    decor.addView(scrimView, barParams);
                    scrim = scrimView;
                }

                grActive = true;
                Log.i(TAG, "gr palette up dial=" + dialPx + " row=" + rowTop + "+" + rowHeight
                    + " dialTop=" + dialTop + " strip=" + stripBottom + " stripTop=" + stripTop
                    + " shutter=" + shutterBottom + " lift=" + dp(GR_PANEL_LIFT_DP)
                    + " layout=" + (ring ? "ring" : "strip")
                    + " bar=" + barTop + ".." + barBottom
                    + " hidden=" + hidden.size());
            } catch (Throwable error) {
                Log.e(TAG, "gr palette failed", error);
                closeGr(false);
            }
        }

        /** The camera's GR styles, read off its strip so the palette can carry them instead. */
        GrPanel.Styles grStyles() {
            View list = find(GR_STYLE_LIST);
            if (!(list instanceof ViewGroup)) {
                Log.i(TAG, "no GR style strip: the column carries parameters only");
                return null;
            }
            ViewGroup group = (ViewGroup) list;
            final List<View> chips = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            int current = -1;
            for (int i = 0; i < group.getChildCount(); i++) {
                View chip = group.getChildAt(i);
                String label = textOf(chip);
                if (label == null || label.isEmpty()) continue;
                chips.add(chip);
                labels.add(label);
                if (chip.isSelected() || chip.isActivated()) current = chips.size() - 1;
            }
            Log.i(TAG, "GR styles: " + labels + " current=" + current);
            if (chips.isEmpty()) return null;
            final int now = current;
            final StyleCall call = styleCall(group);
            return new GrPanel.Styles() {
                @Override public List<String> labels() {
                    return labels;
                }

                @Override public int current() {
                    return now;
                }

                @Override public void select(int index) {
                    if (index < 0 || index >= labels.size()) return;
                    // Preferred: the camera's own "this style was chosen" callback, which is where a
                    // tap on the strip ends up. It takes the row index and nothing else, so it does
                    // not care that the strip is hidden and its rows no longer know their positions.
                    if (call != null && call.invoke(index)) {
                        Log.i(TAG, "style -> " + index + " (" + labels.get(index) + ") via "
                            + call.name());
                        return;
                    }
                    // Fallback: a real click, but only on a row that still knows where it sits -
                    // clicking one that does not hands the camera -1 and walks off its style table.
                    View list = find(GR_STYLE_LIST);
                    if (!(list instanceof ViewGroup)) return;
                    ViewGroup group = (ViewGroup) list;
                    if (index >= group.getChildCount()) {
                        Log.i(TAG, "style " + index + " is not in the strip ("
                            + group.getChildCount() + " rows)");
                        return;
                    }
                    View chip = group.getChildAt(index);
                    if (!addressable(group, chip)) {
                        Log.i(TAG, "style " + index + " has no adapter position; left alone");
                        return;
                    }
                    Log.i(TAG, "style -> " + index + " (" + textOf(chip) + ") clicked="
                        + chip.performClick());
                }

                @Override public Object recipe(int index) {
                    if (index < 0 || index >= labels.size()) return grRecipe(null);
                    return grRecipe(labels.get(index));
                }
            };
        }

        /** The camera's style callback, ready to be called with a row index. */
        private final class StyleCall {
            private final Object target;
            private final Method method;

            StyleCall(Object target, Method method) {
                this.target = target;
                this.method = method;
            }

            String name() {
                return target.getClass().getSimpleName() + "." + method.getName();
            }

            boolean invoke(int index) {
                try {
                    method.invoke(target, index);
                    return true;
                } catch (Throwable error) {
                    Log.i(TAG, "style callback failed: " + error);
                    return false;
                }
            }
        }

        /**
         * Finds the callback the camera installs on its style list: the adapter keeps a listener
         * whose type declares a single-int method, and that is what a choice on the strip calls.
         */
        StyleCall styleCall(ViewGroup list) {
            try {
                Object adapter = XposedHelpers.callMethod(list, "getAdapter");
                if (adapter == null) return null;
                for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        Method method = intMethod(field.getType());
                        if (method == null) continue;
                        field.setAccessible(true);
                        Object target = field.get(adapter);
                        if (target == null) continue;
                        Log.i(TAG, "style callback " + field.getName() + " = "
                            + target.getClass().getName() + "." + method.getName());
                        return new StyleCall(target, method);
                    }
                }
            } catch (Throwable error) {
                Log.i(TAG, "style callback lookup skipped: " + error);
            }
            return null;
        }

        /** A type's own single-int, void-returning method, when it has exactly one of them. */
        Method intMethod(Class<?> type) {
            Method found = null;
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != int.class
                    || method.getReturnType() != void.class) {
                    continue;
                }
                if (found != null) return null;
                found = method;
            }
            return found;
        }

        /**
         * Whether a row of the camera's strip still knows where it sits in the list. Clicking one
         * whose adapter position is unknown hands the camera -1, so it is left alone instead.
         */
        boolean addressable(ViewGroup group, View chip) {
            try {
                Object holder = XposedHelpers.callMethod(group, "getChildViewHolder", chip);
                Object position = holder == null ? null
                    : XposedHelpers.callMethod(holder, "getAbsoluteAdapterPosition");
                return position instanceof Integer && ((Integer) position) >= 0;
            } catch (Throwable error) {
                Log.i(TAG, "adapter position check skipped: " + error);
                return true;
            }
        }

        /** The first non-empty text under a view, which is how the style chips are labelled. */
        String textOf(View view) {
            if (view instanceof TextView) {
                CharSequence text = ((TextView) view).getText();
                if (text != null && text.length() > 0) return text.toString();
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    String text = textOf(group.getChildAt(i));
                    if (text != null && !text.isEmpty()) return text;
                }
            }
            return null;
        }

        /**
         * The dial's colour recipe for a GR style. The camera keys these by filter name, so the
         * style's own label is tried first and the neutral default is the fallback.
         */
        Object grRecipe(String style) {
            try {
                String key = style == null ? "default" : style.trim().toLowerCase();
                boolean known = !key.isEmpty() && (Boolean) stat(
                    "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors",
                    "hasPalette", key);
                Object recipe = known ? stat(
                    "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors",
                    "get", key) : null;
                if (recipe == null) {
                    recipe = stat("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors",
                        "get", "default");
                }
                Log.i(TAG, "gr palette recipe style=" + style + " key=" + key + " known=" + known
                    + " present=" + (recipe != null));
                return recipe;
            } catch (Throwable error) {
                Log.i(TAG, "gr palette recipe failed: " + error);
                return null;
            }
        }

        void closeGr(boolean fromUser) {
            if (!grActive) return;
            hide();
        }

        void open() {
            if (shelf != null) {
                hide();
                return;
            }
            boolean retro = retroOpen;
            // The GR page's parameters live in the camera's own panel rows, which only exist once
            // that panel has been built - and the camera builds it when its GR menu comes up. From
            // then on the button opens and closes the dial on this page like it does everywhere
            // else, so a menu that has been put away is not a dead end.
            if (isGrMode(currentMode())) {
                if (!grPaletteEnabled()) return;
                if (!GrEffectValues.available()) {
                    Log.i(TAG, "GR palette: the camera has not built its GR panel yet");
                    return;
                }
                grManual = true;
                openGrPalette(null);
                return;
            }
            Object presenter = palettePresenter(retro ? MODE_RETRO : MODE_MASTER);
            // 哈苏超清 always takes the shelf page (filter row + dial). The master page has no filter
            // row at all, and the pro presenter only shows up after the camera has built its palette
            // once - so the same button opened the good page first and a filterless page every time
            // after. Ignoring the presenter here keeps the page stable and writes through the LUT
            // manager, the path POP already uses.
            if (!retro && isHighPixelMode(currentMode())) presenter = null;
            // Master goes through the pro presenter. Without one - POP, and the video pages, whose
            // pro feature is never built - tone and saturation still work, because they live in the
            // LUT manager rather than in a presenter.
            if (presenter == null
                && !(retro || isHighPixelMode(currentMode()) || (isVideoMode(currentMode()) && lut() != null))) {
                return;
            }
            // Only the pro page has room for - and matches - the camera's own lifted arrangement.
            // Everything else keeps its layout and docks the dial under the shutter instead. POP is
            // stacked whether or not a pro presenter happens to be alive: its preview card ends at
            // 1590 with the filter strip filling 1590..1770, so the lift would bury that strip.
            boolean stacked = retro || presenter == null;
            View panel;
            Object recipe;
            String filter;
            try {
                // ef.m$b only accepts an ef.m; anything else is a NoSuchMethodError that would abort
                // the whole open, so the callback is built only for a presenter that really is one.
                Object holder = presenter == null || !isProPresenter(presenter) ? null
                    : XposedHelpers.newInstance(cls("ef.m$b"), presenter);
                panel = (View) XposedHelpers.newInstance(
                    cls("com.oplus.ocs.camera.ipuapi.process.filter.palette.PalettePanelView"), activity);
                filter = presenter == null ? null
                    : (String) call(byType(presenter, type("yg.a")), "R5",
                        ((Number) call(presenter, "L2")).intValue());
                if (filter == null || filter.isEmpty()) filter = "default";
                recipe = stat("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors", "get", filter);
                if (recipe == null) {
                    recipe = stat("com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors", "get", "default");
                }
                call(panel, "setColorRecipe", recipe);
                int tone = value(presenter, "P0");
                int saturation = value(presenter, "O0");
                call(panel, "setCurrentToneAndSaturation", tone, saturation);

                // Master copies the stock arrangement instead of inventing one. Measured with the
                // paldump tree dump on this device, photo mode - with its own palette open - does
                // exactly this: lifts the whole control panel by
                // dimen/palette_control_view_translation_4_3 (95dp), hides the mode strip, the zoom
                // row and the manual-parameter rows, and puts the 96dp dial at control-panel-top +
                // 351px. Its preview frame never moves (330..1770 in both states), so "the preview
                // shrinks and lifts" was only ever the hidden rows plus the lifted panel.
                controlBar = findByIds(CANDIDATE_CONTROL_IDS);
                restore();
                int panelTop = controlBar != null ? screenTop(controlBar)
                    : decor.getHeight() - dp(202);
                int dialPx = dial();
                int dialTop;
                // 哈苏超清 has no filter UI of its own; the row is drawn here and sits above the dial.
                View filterBar = null;
                int filterBarH = 0;
                if (stacked) {
                    // No room for the lift on these pages. POP's preview card ends at 1590 with its
                    // filter strip filling 1590..1770, and the video page's preview runs to 2070 with
                    // the control panel sitting on top of it, so raising the panel by 95dp would drive
                    // the shutter (1794-95dp = 1509..1725) straight onto the picture. The dial
                    // therefore docks in the empty band under the shutter and only the mode strip
                    // steps aside; the preview and everything else stay where they are.
                    for (String id : RETRO_HIDE_IDS) {
                        View view = find(id);
                        if (view == null || view.getVisibility() != View.VISIBLE) continue;
                        hidden.put(view, view.getVisibility());
                        view.setVisibility(View.GONE);
                    }
                    if (isHighPixelMode(currentMode())) {
                        // The 哈苏超清 page has a 4:3 preview that ends well above the shutter row, so
                        // the dial sits on the lower edge of the picture like the camera's own panel
                        // does; docking it under the shutter left it floating over the zoom row.
                        View frame = find("frame_layout");
                        int frameBottom = frame != null && frame.getHeight() > 0
                            ? screenTop(frame) + frame.getHeight() : decor.getHeight() - dp(500);
                        // The zoom row sits on the bottom edge of the picture; the block goes above it
                        // rather than over it, so both stay usable.
                        int zoomTop = firstTop("zoom_seek_bar_container", "zoom_seek_bar", "inertial_zoom_view");
                        if (zoomTop > dp(200) && zoomTop < frameBottom) frameBottom = zoomTop;
                        dialTop = Math.max(dp(140), frameBottom - dialPx - dp(16));
                        filterBar = HighPixelFilters.bar(activity, null);
                        if (filterBar != null) {
                            filterBarH = dp(34);
                            dialTop = Math.max(dp(96), dialTop - filterBarH - dp(6));
                        } else {
                            Log.i(TAG, "no filter row for this page: " + HighPixelFilters.describe());
                        }
                    } else {
                        dialTop = decor.getHeight() - dialPx - dp(RETRO_DIAL_BOTTOM_DP);
                    }
                } else {
                    if (controlBar != null) controlBar.setTranslationY(-dp(PALETTE_PANEL_LIFT_DP));
                    panelTop = controlBar != null ? screenTop(controlBar)
                        : decor.getHeight() - dp(202);
                    for (String id : PALETTE_HIDE_IDS) {
                        View view = find(id);
                        if (view == null || view.getVisibility() != View.VISIBLE) continue;
                        hidden.put(view, view.getVisibility());
                        view.setVisibility(View.GONE);
                    }
                    dialTop = panelTop + dp(PALETTE_DIAL_TOP_DP);

                    // Round the preview off the same way photo mode does while its palette is up. The
                    // image comes from gl_root_view, which cannot be clipped by an outline, so the
                    // card is painted as a mask: black outside the inset rounded rectangle.
                    View frame = find("frame_layout");
                    if (frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
                        FrameLayout.LayoutParams maskParams =
                            new FrameLayout.LayoutParams(frame.getWidth(), frame.getHeight());
                        maskParams.leftMargin = screenLeft(frame);
                        maskParams.topMargin = screenTop(frame);
                        previewMask = new PreviewCardMask(activity,
                            dp(PREVIEW_CARD_INSET_DP), dp(PREVIEW_CARD_RADIUS_DP));
                        decor.addView(previewMask, maskParams);
                    }

                    // The thumbnail rides with the control panel, which would park it next to the
                    // shutter over the preview. Photo mode puts its 重置 button in the bottom-left
                    // corner of the palette row (1932..2039), so the thumbnail goes there.
                    thumb = find("thumbnail");
                    if (thumb != null && thumb.getHeight() > 0) {
                        thumbBase = thumb.getTranslationY();
                        thumbShift = (dialTop + dialPx / 2f) - (screenTop(thumb) + thumb.getHeight() / 2f);
                        thumb.setTranslationY(thumbBase + thumbShift);
                    }
                }

                if (filterBar != null) {
                    LinearLayout block = new LinearLayout(activity);
                    block.setOrientation(LinearLayout.VERTICAL);
                    block.addView(filterBar,
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, filterBarH));
                    LinearLayout.LayoutParams dialInBlock = new LinearLayout.LayoutParams(dialPx, dialPx);
                    dialInBlock.gravity = Gravity.CENTER_HORIZONTAL;
                    dialInBlock.topMargin = dp(6);
                    block.addView(panel, dialInBlock);
                    FrameLayout.LayoutParams blockParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
                    blockParams.leftMargin = dp(4);
                    blockParams.rightMargin = dp(4);
                    blockParams.topMargin = dialTop;
                    decor.addView(block, blockParams);
                    shelf = block;
                    decor.bringChildToFront(block);
                    final View barView = filterBar;
                    final int barTop = dialTop;
                    block.post(() -> Log.i(TAG, "filter block " + block.getWidth() + "x" + block.getHeight()
                        + " top=" + block.getTop() + " bar=" + barView.getHeight()
                        + " dial=" + panel.getHeight() + " dialTop=" + barTop));
                } else {
                    FrameLayout.LayoutParams dialParams = new FrameLayout.LayoutParams(
                        dialPx, dialPx, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                    dialParams.topMargin = dialTop;
                    decor.addView(panel, dialParams);
                    shelf = panel;
                    decor.bringChildToFront(panel);
                }

                readout = buildReadout();
                if (readout != null) readout.show(tone, saturation);

                // Anything tapped over the preview closes the palette, as in photo mode. The scrim
                // stops above the control panel - and, where there is one, above the retro filter
                // strip too, so the strip keeps taking its own taps and drags.
                int scrimBottom = Math.max(dp(80), Math.min(panelTop, decor.getHeight()));
                if (stacked) {
                    View strip = find("retro_camera_filter_scrollview");
                    if (strip != null && strip.getHeight() > 0) {
                        scrimBottom = Math.max(dp(80), Math.min(scrimBottom, screenTop(strip)));
                    }
                    // The dial is docked under the shutter on these pages, and the control bar can sit
                    // below it: without this the scrim covered the dial, so a drag landed on the scrim
                    // and only closed the palette.
                    scrimBottom = Math.max(dp(80), Math.min(scrimBottom, dialTop - dp(6)));
                }
                View scrimView = new View(activity);
                scrimView.setBackgroundColor(0x01000000);
                scrimView.setClickable(true);
                scrimView.setOnClickListener(v -> hide());
                decor.addView(scrimView, new FrameLayout.LayoutParams(-1, Math.max(dp(1), scrimBottom), Gravity.TOP));
                scrim = scrimView;
                postDump("master-palette");
                // Two later snapshots: if the user goes on to open the filter page, the state that
                // broke the drag is captured even when the auto-close above does not fire.
                if (diagnostics) {
                    decor.postDelayed(() -> dumpTree("master-palette+3s"), 3000);
                    decor.postDelayed(() -> dumpTree("master-palette+6s"), 6000);
                }

                Pending pending = new Pending(presenter, holder, panel, readout,
                    retro ? MODE_RETRO : MODE_MASTER, tone, saturation);
                this.pending = pending;
                // No presenter means the LUT manager is the only write path, so the camera's mode
                // gate has to be answered for us while the dial is up (see install).
                directPaletteOpen = holder == null;
                forcePaletteFlag();
                if (directPaletteOpen) ensureToneEnv();
                // On a page the camera keeps the combined LUT away from (the video page) the palette
                // is computed and then dropped, so the preview has to be handed the table - and it
                // has to keep being handed over after the dial closes, because the camera rebuilds
                // its program when recording starts a new surface.
                if (directPaletteOpen && isVideoMode(currentMode())) {
                    videoPaletteWanted = true;
                    applyPreviewLut();
                }
                panel.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        v.post(pending.update);
                    }
                    return false;
                });
                call(panel, "setOnToneSaturationChangeListener", Proxy.newProxyInstance(loader,
                    new Class<?>[] { cls("com.oplus.ocs.camera.ipuapi.process.filter.palette."
                        + "PalettePanelView$OnToneSaturationChangeListener") },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "onToneSaturationChanged":
                                pending.change(((Number) args[0]).intValue(), ((Number) args[1]).intValue());
                                return null;
                            case "toString":
                                return "AuraMasterPaletteListener";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    }));

                StringBuilder ids = new StringBuilder();
                for (String id : GEOMETRY_IDS) ids.append(geometry(id)).append(' ');
                Log.i(TAG, "opened page=" + (retro ? "retro" : presenter == null ? "other" : "master")
                    + " mode=" + currentMode()
                    + " layout=" + (stacked ? "docked" : "lifted")
                    + " via=" + (holder != null ? "presenter" : "lut")
                    + " lut[" + lutState() + "]"
                    + " modeImpl=" + describeClass(modeObject())
                    + " filter=" + filter + " tone=" + tone + " saturation=" + saturation
                    + " dial=" + dialPx + " dialTop=" + dialTop + " panelTop=" + panelTop
                    + " scrim=" + scrimBottom + " hidden=" + hidden.size()
                    + " card=" + (previewMask != null) + " thumbShift=" + thumbShift
                    + " readout=" + (readout != null)
                    + (readout == null ? "" : "(tone=" + (readout.tone != null)
                        + ",sat=" + (readout.saturation != null) + ")")
                    + " controlBar=" + entryName(controlBar)
                    + " geom[" + ids.toString().trim() + "]");
            } catch (Throwable error) {
                hide();
                Log.e(TAG, "open failed", error);
            }
        }
    }

    /**
     * Paints everything outside an inset rounded rectangle black, i.e. a rounded "card" the live
     * preview shows through. Used because the preview lives in gl_root_view, a GL surface that no
     * outline/clipToOutline can round - photo mode gets the same look from its own renderer.
     */
    private static final class PreviewCardMask extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float inset;
        private final float radius;

        PreviewCardMask(Context context, float inset, float radius) {
            super(context);
            this.inset = inset;
            this.radius = radius;
            paint.setColor(0xFF000000);
            setClickable(false);
            setFocusable(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
            path.addRoundRect(inset, 0f, getWidth() - inset, getHeight(), radius, radius,
                Path.Direction.CW);
            canvas.drawPath(path, paint);
        }
    }

    /**
     * The app's 影调/饱和度 readout views. Written with setText, which the rolling-number view
     * handles itself, so no internal API is needed.
     */
    private static final class Readout {
        final View view;
        private final TextView tone;
        private final TextView saturation;

        Readout(View view, TextView tone, TextView saturation) {
            this.view = view;
            this.tone = tone;
            this.saturation = saturation;
        }

        void show(int toneValue, int saturationValue) {
            set(tone, toneValue);
            set(saturation, saturationValue);
        }

        private static void set(TextView view, int value) {
            if (view == null) return;
            String text = String.valueOf(value);
            if (!text.contentEquals(view.getText())) view.setText(text);
        }
    }

    /** Debounced palette value push: 32 ms coalescing, then presenter.a(value, key). */
    private static final class Pending {
        private final Object presenter;
        private final Object callback;
        private final View panel;
        private final Readout readout;
        private final String mode;
        private final Object toneKey = key("P0");
        private final Object saturationKey = key("O0");
        private final Runnable update = this::flush;
        private int tone;
        private int saturation;
        private int appliedTone;
        private int appliedSaturation;
        private int lastLoggedTone = Integer.MIN_VALUE;
        private int lastLoggedSaturation = Integer.MIN_VALUE;
        private boolean queued;

        Pending(Object presenter, Object callback, View panel, Readout readout, String mode,
                int tone, int saturation) {
            this.presenter = presenter;
            this.callback = callback;
            this.panel = panel;
            this.readout = readout;
            this.mode = mode;
            this.tone = tone;
            this.appliedTone = tone;
            this.saturation = saturation;
            this.appliedSaturation = saturation;
        }

        void change(int tone, int saturation) {
            this.tone = tone;
            this.saturation = saturation;
            if (!queued) {
                queued = true;
                panel.postDelayed(update, 32);
            }
        }

        void flush() {
            panel.removeCallbacks(update);
            queued = false;
            // This runs from a posted tick on the camera's main thread, so anything the camera throws
            // in here used to take the whole process down - which is what happened on the GR page
            // (XposedHelpers$InvocationTargetError: NPE, out of Pending.flush). A hook failure must
            // never be fatal: log it and keep the camera alive.
            try {
                apply();
            } catch (Throwable error) {
                Log.e(TAG, "flush failed tone=" + tone + " saturation=" + saturation, error);
            }
        }

        private void apply() throws Throwable {
            // Mode left (or the camera rebuilt its presenter): stop pushing values into it. With no
            // presenter at all (POP built outside the pro feature) the direct route is the only one,
            // and it stays valid for as long as the palette is on screen.
            if (presenter != null && palettePresenter(mode) != presenter) return;
            if (readout != null) readout.show(tone, saturation);
            if (tone != appliedTone) {
                if (callback != null) call(callback, "a", tone, toneKey);
                else {
                    writeDirect("P0", tone);
                    notifyMode("P0", tone);
                }
                appliedTone = tone;
            }
            if (saturation != appliedSaturation) {
                if (callback != null) call(callback, "a", saturation, saturationKey);
                else {
                    writeDirect("O0", saturation);
                    notifyMode("O0", saturation);
                }
                appliedSaturation = saturation;
            }
            if (tone != lastLoggedTone || saturation != lastLoggedSaturation) {
                lastLoggedTone = tone;
                lastLoggedSaturation = saturation;
                Log.i(TAG, "apply tone=" + tone + " saturation=" + saturation
                    + " via=" + (callback != null ? "presenter" : "lut"));
            }
            // The combined LUT changed, so re-hand it to the preview where the camera does not.
            applyPreviewLut();
        }
    }
}
