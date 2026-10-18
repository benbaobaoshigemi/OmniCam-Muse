#!/system/bin/sh

MODDIR=${0%/*}
PREF_DIR=/data/user/0/com.oplus.camera/shared_prefs
PREF_FILE="$PREF_DIR/override_config_data.xml"
APP=/data/user/0/com.oplus.camera
OUT=$APP/files/ricoh_gr/lmt
GR_AVAILABLE='0.6(14),1(23),2(47),3(70),6(139),10(230)'
GR_MARKED='1.2173913(28),1.7391304(40)'

until [ -d "$APP" ]; do
    sleep 1
done

APP_UID=$(stat -c %u "$APP")
mkdir -p "$PREF_DIR"
chown "$APP_UID:$APP_UID" "$PREF_DIR"
chmod 0770 "$PREF_DIR"

if [ ! -f "$PREF_FILE" ]; then
    printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map>' '</map>' > "$PREF_FILE"
fi

set_config() {
    KEY=$1
    VALUE=$2
    if grep -q "name=\"$KEY\"" "$PREF_FILE"; then
        sed -i "s#<string name=\"$KEY\">[^<]*</string>#<string name=\"$KEY\">$VALUE</string>#" "$PREF_FILE"
    else
        sed -i "/<\/map>/i\    <string name=\"$KEY\">$VALUE</string>" "$PREF_FILE"
    fi
}

    set_config com.oplus.gr.mode.support 1
    set_config com.oplus.camera.mode.data.db.version 102
set_config com.oplus.available.gr.mode.zoomvalues "$GR_AVAILABLE"
set_config com.oplus.gr.mode.marked.zoomvalues "$GR_MARKED"
set_config com.oplus.camera.capture.hdr.cap.mode.value 'common,portrait,professional,night,highPixel,xpan,underWater,telephoto,gr,retroCamera'
set_config com.oplus.camera.preview.hdr.cap.mode.value 'common,professional,night,highPixel,xpan,underWater,telephoto,gr'

chown "$APP_UID:$APP_UID" "$PREF_FILE"
chmod 0660 "$PREF_FILE"
restorecon "$PREF_FILE"

mkdir -p "$OUT"
chown "$APP_UID:$APP_UID" "$APP/files"
chmod 0770 "$APP/files"
for ORIGINAL in /odm/etc/camera/basictone/lmt/*; do
    NAME=${ORIGINAL##*/}
    case "$NAME" in
        LMTPhotoLut*|SCLut*)
            # Palette LUTs stay in the shared ODM tree; GR uses its own profile.
            continue
            ;;
        SC16*|SC32*|CWCM*)
            cp "$MODDIR/payload/gr_adjustment_lmt/$NAME" "$OUT/$NAME"
            ;;
        *)
            if [ -L "$OUT/$NAME" ]; then rm "$OUT/$NAME"; fi
            if [ -d "$ORIGINAL" ]; then
                mkdir -p "$OUT/$NAME"
                cp -R "$ORIGINAL/." "$OUT/$NAME/"
            else
                cp "$ORIGINAL" "$OUT/$NAME"
            fi
            ;;
    esac
done
chown -R "$APP_UID:$APP_UID" "$APP/files/ricoh_gr"
restorecon -R "$APP/files/ricoh_gr"

# Master colour (大师色彩): engine data, scene front stage profiles and look tables (Lumix / film). Baked tables
# (files/jiege/mastercolor/live) come from the camera; they are dropped when the module changes so they
# are rebuilt from the new data. The selected look (written by the hook) survives the refresh.
MASTER_COLOR=$APP/files/jiege/mastercolor
MASTER_COLOR_STAMP=$(grep '^versionCode=' "$MODDIR/module.prop")
if [ "$(cat "$MASTER_COLOR/stamp" 2>/dev/null)" != "$MASTER_COLOR_STAMP" ] || ! cmp -s "$MODDIR/payload/master_color/hncs_engine.bin" "$MASTER_COLOR/hncs_engine.bin" || ! cmp -s "$MODDIR/payload/master_color/scene.bin" "$MASTER_COLOR/scene.bin" || ! diff -rq "$MODDIR/payload/master_color/looks" "$MASTER_COLOR/looks" >/dev/null 2>&1; then
    LOOK=$(cat "$MASTER_COLOR/look" 2>/dev/null)
    rm -rf "$MASTER_COLOR"
    mkdir -p "$MASTER_COLOR"
    cp "$MODDIR/payload/master_color/hncs_engine.bin" "$MASTER_COLOR/hncs_engine.bin"
    cp "$MODDIR/payload/master_color/scene.bin" "$MASTER_COLOR/scene.bin"
    cp -r "$MODDIR/payload/master_color/looks" "$MASTER_COLOR/looks"
    echo "$MASTER_COLOR_STAMP" > "$MASTER_COLOR/stamp"
    [ -n "$LOOK" ] && echo "$LOOK" > "$MASTER_COLOR/look"
fi
mkdir -p "$MASTER_COLOR/live"
# GR watermark fonts: the styles name their fonts by URL, which the camera cannot load. Fonts the
# gallery already downloaded for its watermark editor are copied where the GR hook finds them
# (FZLTZCHK is also in the camera's own files/video_watermark).
GALLERY_FONTS=/data/user/0/com.coloros.gallery3d/files/watermark_master/materia
mkdir -p "$APP/files/jiege/fonts"
for FONT in GilroySemiBold.otf FZLTZCHK.TTF; do
    [ -f "$GALLERY_FONTS/$FONT" ] && ! cmp -s "$GALLERY_FONTS/$FONT" "$APP/files/jiege/fonts/$FONT" && cp "$GALLERY_FONTS/$FONT" "$APP/files/jiege/fonts/$FONT"
done
chown -R "$APP_UID:$APP_UID" "$APP/files/jiege"
chmod 0771 "$APP/files/jiege" "$MASTER_COLOR" "$MASTER_COLOR/live"
restorecon -R "$APP/files/jiege"

# Stage POP before the gallery wait so camera resources are ready at startup.
ASSET_DIR="$APP/files/pop_port"
PLD_APP="$APP/files/odm/etc/camera/pld_watermark"
set_config com.oplus.feature.retro.camera.support 1
# Flash brightness slider (柔和/反差); the camera drives it through com.oplus.flash.IntensityControl.
set_config com.oplus.flashlevel.configurable.support 1
set_config com.oplus.feature.tilt.shift.photo.support 1
# Tilt-shift: mode_data.db is built once from the feature flags. If it has no tiltShift row yet, drop it once
# (the marker keeps this to a single time) so the camera recreates it with the tilt-shift flag on.
TILT_MARK="$MODDIR/tilt_db_reset"
TILT_DB="$APP/databases/mode_data.db"
if [ ! -f "$TILT_MARK" ] && [ -f "$TILT_DB" ] && ! grep -aq tiltShift "$TILT_DB"; then
    am force-stop com.oplus.camera
    rm -f "$TILT_DB" "$TILT_DB-journal" "$TILT_DB-wal" "$TILT_DB-shm"
fi
touch "$TILT_MARK"
chown "$APP_UID:$APP_UID" "$PREF_FILE"
chmod 0660 "$PREF_FILE"
restorecon "$PREF_FILE" 2>/dev/null || true
mkdir -p "$ASSET_DIR/setting_Retro" "$ASSET_DIR/meishe_lut" "$ASSET_DIR/pld_watermark" "$ASSET_DIR/apk_resources/json" "$ASSET_DIR/apk_resources/img" "$PLD_APP"
cp -f "$MODDIR/payload/setting_Retro/"* "$ASSET_DIR/setting_Retro/"
cp -f "$MODDIR/payload/meishe_lut/"* "$ASSET_DIR/meishe_lut/"
cp -a "$MODDIR/payload/pld_watermark/." "$ASSET_DIR/pld_watermark/"
cp -a "$MODDIR/payload/pld_watermark/." "$PLD_APP/"
cp -a "$MODDIR/payload/apk_resources/." "$ASSET_DIR/apk_resources/"
chown -R "$APP_UID:$APP_UID" "$APP/files/pop_port" "$APP/files/odm"
find "$ASSET_DIR" "$PLD_APP" -type d -exec chmod 0755 {} \;
find "$ASSET_DIR" "$PLD_APP" -type f -exec chmod 0644 {} \;
restorecon -R "$ASSET_DIR" "$APP/files/odm" 2>/dev/null || true

# POP capture decision (X8U only). The stock /odm copy of oplus_camera_preview_decision_config.json
# is an encrypted container, so the POP BasicTone branches (rear/front retro + non-POP guard) never
# reach the capture decision and the POP sliders do nothing on the shot. The patched plaintext
# (payload_pkj110/pop_decision.json, tools/patch_x8u_pop_decision.py) is staged here and served by
# pop_path_remap.cpp in place of the stock file. PMA110 keeps its own X9U decision, which already
# carries the POP branches and is bound over /odm by post-fs-data.sh.
if [ "$(getprop ro.product.model 2>/dev/null)" = PKJ110 ]; then
    cp -f "$MODDIR/payload_pkj110/pop_decision.json" "$ASSET_DIR/oplus_camera_preview_decision_config.json"
    chown "$APP_UID:$APP_UID" "$ASSET_DIR/oplus_camera_preview_decision_config.json"
    chmod 0644 "$ASSET_DIR/oplus_camera_preview_decision_config.json"
    restorecon "$ASSET_DIR/oplus_camera_preview_decision_config.json" 2>/dev/null || true
fi

# Normal-mode 25MP unlock (tools/patch_25mp.py): the relaxed config copies; the settings hook adds the
# "enabled" marker while the switch is on and unlock25mp.cpp redirects reads to these copies.
#
# The copies are generated from the X9 Ultra stock /odm, so they only describe sensor modes PMA110 has.
# On PKJ110 the redirected decision tree hands the capture gate a null sensor-mode array and every
# mode's shutter silently does nothing - unlock25mp.cpp therefore also requires the profile file below
# to say PMA110. Written here because this is the only place that runs with the app's uid available.
PROFILE_FILE="$APP/files/jiege/profile"
mkdir -p "$APP/files/jiege"
if [ "$(getprop ro.product.model 2>/dev/null)" = PMA110 ]; then
    echo PMA110 > "$PROFILE_FILE"
else
    echo PKJ110 > "$PROFILE_FILE"
fi
chown "$APP_UID:$APP_UID" "$PROFILE_FILE"
chmod 0644 "$PROFILE_FILE"
restorecon "$PROFILE_FILE" 2>/dev/null || true

UNLOCK_DIR="$APP/files/jiege/unlock25mp"
mkdir -p "$UNLOCK_DIR"
rm -f "$UNLOCK_DIR"/live50*  # staged by 1.3.0-1.3.5 (live 50MP, removed)
cp -f "$MODDIR/payload/unlock25mp/"* "$UNLOCK_DIR/"
chown -R "$APP_UID:$APP_UID" "$UNLOCK_DIR"
chmod 0771 "$UNLOCK_DIR"
find "$UNLOCK_DIR" -type f -exec chmod 0644 {} \;
restorecon -R "$UNLOCK_DIR" 2>/dev/null || true

# 哈苏超清 200MP without the big-face gate (tools/patch_200m_face.py): the decision copies; the settings
# hook adds the "enabled" marker while the switch is on and face200m.cpp redirects the decision read.
FACE_DIR="$APP/files/jiege/face200m"
mkdir -p "$FACE_DIR"
cp -a "$MODDIR/payload/face200m/." "$FACE_DIR/"
chown -R "$APP_UID:$APP_UID" "$FACE_DIR"
find "$FACE_DIR" -type d -exec chmod 0771 {} \;
find "$FACE_DIR" -type f -exec chmod 0644 {} \;
restorecon -R "$FACE_DIR" 2>/dev/null || true

GALLERY=/data/user/0/com.coloros.gallery3d
until [ -d "$GALLERY" ]; do
    sleep 1
done
GALLERY_UID=$(stat -c %u "$GALLERY")
GALLERY_PREF="$GALLERY/shared_prefs/business_featureSwitch_config.xml"
WAIT=0
while [ ! -f "$GALLERY_PREF" ] && [ "$WAIT" -lt 120 ]; do
    sleep 1
    WAIT=$((WAIT + 1))
done
if [ -f "$GALLERY_PREF" ]; then
    if grep -q 'name="business_featureSwitch_is_camera_gr_supported"' "$GALLERY_PREF"; then
        sed -i 's/name="business_featureSwitch_is_camera_gr_supported" value="false"/name="business_featureSwitch_is_camera_gr_supported" value="true"/' "$GALLERY_PREF"
    else
        sed -i '/<\/map>/i\    <boolean name="business_featureSwitch_is_camera_gr_supported" value="true" />' "$GALLERY_PREF"
    fi
    chown "$GALLERY_UID:$GALLERY_UID" "$GALLERY_PREF"
    chmod 0660 "$GALLERY_PREF"
    restorecon "$GALLERY_PREF"
fi
STYLE_OUT="$GALLERY/files/ricoh_gr_styles"
mkdir -p "$STYLE_OUT"
cp "$MODDIR/payload/watermark_master_styles/"*.json "$STYLE_OUT/"
if [ -d "$MODDIR/payload/watermark_drawables" ]; then
    cp "$MODDIR/payload/watermark_drawables/"*.webp "$STYLE_OUT/" 2>/dev/null || true
fi
CAM_ML="$APP/files/mode_limit_watermark"
mkdir -p "$CAM_ML"
for i in 1 2 3 4 5; do
    ID="gr_style_${i}"
    NAME="gr_style_${i}.json"
    JSON="$MODDIR/payload/watermark_master_styles/$NAME"
    if [ -f "$JSON" ]; then
        mkdir -p "$CAM_ML/$ID"
        cp -f "$JSON" "$CAM_ML/$ID/$NAME"
        cp -f "$JSON" "$CAM_ML/$NAME"
        if [ -d "$MODDIR/payload/watermark_drawables" ]; then
            cp -f "$MODDIR/payload/watermark_drawables/"*.webp "$CAM_ML/$ID/" 2>/dev/null || true
        fi
    fi
done
if [ -d "$MODDIR/payload/watermark_drawables" ]; then
    cp -f "$MODDIR/payload/watermark_drawables/"*.webp "$CAM_ML/" 2>/dev/null || true
fi
chown -R "$GALLERY_UID:$GALLERY_UID" "$STYLE_OUT"
chmod 0644 "$STYLE_OUT"/*
restorecon -R "$STYLE_OUT"
# Camera unit SDK with the palette classes (CombineLut, PaletteColorTable) for the gallery's
# palette editor; the gallery hook loads it in place of the system copy. Dex must be read-only.
IPU_OUT="$GALLERY/files/jiege_ipu"
mkdir -p "$IPU_OUT"
cmp -s "$MODDIR/payload/ipu/com.oplus.camera.unit.sdk.jar" "$IPU_OUT/com.oplus.camera.unit.sdk.jar" || cp -f "$MODDIR/payload/ipu/com.oplus.camera.unit.sdk.jar" "$IPU_OUT/"
chown "$GALLERY_UID:$GALLERY_UID" "$IPU_OUT"
chmod 0711 "$IPU_OUT"
chown 0:0 "$IPU_OUT/com.oplus.camera.unit.sdk.jar"
chmod 0444 "$IPU_OUT/com.oplus.camera.unit.sdk.jar"
restorecon "$IPU_OUT"
# A root-owned file gets no MLS categories from restorecon; take the directory's.
chcon "$(ls -dZ "$IPU_OUT" | awk '{print $1}')" "$IPU_OUT/com.oplus.camera.unit.sdk.jar"
chown -R "$APP_UID:$APP_UID" "$CAM_ML"
find "$CAM_ML" -type f -exec chmod 0644 {} \;
find "$CAM_ML" -type d -exec chmod 0755 {} \;
restorecon -R "$CAM_ML"
