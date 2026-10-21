#!/system/bin/sh
# Bind the tested POP camera directories and GR ISP files.
MODDIR=${0%/*}
LOG=$MODDIR/bind.log
set -u

log() {
    echo "$1" >> "$LOG"
}

bind_file() {
    SOURCE="$1"
    TARGET="$2"
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:vendor_configs_file:s0 "$SOURCE" 2>/dev/null || true
    if mount --bind "$SOURCE" "$TARGET" >> "$LOG" 2>&1; then
        log "file bind ok $TARGET"
    else
        log "file bind FAIL $TARGET"
    fi
}

label_tree() {
    DIR=$1
    chown -R root:root "$DIR"
    find "$DIR" -type d -exec chmod 0755 {} \;
    find "$DIR" -type f -exec chmod 0644 {} \;
    find "$DIR" -exec chcon u:object_r:vendor_configs_file:s0 {} \; 2>>"$LOG" || true
}

bind_merged_dir() {
    LIVE=$1
    EXTRA=$2
    DEST=$3
    TARGET=$4
    log "merge $TARGET from $LIVE"
    if [ ! -d "$LIVE" ]; then
        log "missing live $LIVE"
        return 0
    fi
    rm -rf "$DEST"
    mkdir -p "$DEST"
    cp -a "$LIVE"/. "$DEST"/
    if [ -d "$EXTRA" ]; then
        cp -a "$EXTRA"/. "$DEST"/
    fi
    label_tree "$DEST"
    log "live_count=$(ls "$LIVE" | wc -l) merged_count=$(ls "$DEST" | wc -l)"
    if mount --bind "$DEST" "$TARGET" >> "$LOG" 2>&1; then
        log "dir bind ok $TARGET"
    else
        log "dir bind FAIL $TARGET"
    fi
}

: > "$LOG"
log "begin"

# Release our configuration and resource binds before rebuilding their sources.
# Hybrid Mount and post-mount.sh manage the separate /odm/lib64 mount tree.
for TARGET in $(awk -v root="${MODDIR#/data}/" 'index($4, root) == 1 && index($5, "/odm/etc/") == 1 { print $1, $5 }' /proc/self/mountinfo | sort -rn | cut -d ' ' -f 2); do
    log "release $TARGET"
    umount -l "$TARGET" >> "$LOG" 2>&1 || exit 1
done

for NAME in camera_unit_config camera_unit_feature_config.protobuf oplus_camera_config oplus_camera_algo_switch_config oplus_camera_aps_config oplus_camera_preview_decision_config.json; do
    bind_file "$MODDIR/payload/$NAME" "/odm/etc/camera/config/$NAME"
done
# Forced shutter sound stream at the system volume curve (see tools/patch_audio_volumes.py).
bind_file "$MODDIR/payload/audio_policy_volumes.xml" /odm/etc/audio/audio_policy_volumes.xml

bind_merged_dir /odm/etc/camera/meishe_lut "$MODDIR/payload/meishe_lut" "$MODDIR/merged/meishe_lut" /odm/etc/camera/meishe_lut

BT_EXTRA=$MODDIR/merged/basictone_extra
rm -rf "$BT_EXTRA"
mkdir -p "$BT_EXTRA/setting_Retro"
if [ -d "$MODDIR/payload/setting_Retro" ]; then
    cp -a "$MODDIR/payload/setting_Retro"/. "$BT_EXTRA/setting_Retro"/
fi
cp -a "$MODDIR/payload/x10_basictone"/. "$BT_EXTRA"/
bind_merged_dir /odm/etc/camera/basictone "$BT_EXTRA" "$MODDIR/merged/basictone" /odm/etc/camera/basictone

log "polaroid=$(ls -l /odm/etc/camera/meishe_lut/polaroid_sdr.mslut 2>&1)"
log "host_film=$(ls -l /odm/etc/camera/meishe_lut/fuji-nc.bin 2>&1)"
log "retro_ini=$(ls -l /odm/etc/camera/basictone/setting_Retro/SimTool_Master.ini 2>&1)"
log "host_setting=$(ls -d /odm/etc/camera/basictone/setting 2>&1)"

for LENS in main wide tele ultratele; do
    NAME="com.qti.tuned.lighthouse${LENS}.bin"
    SOURCE="$MODDIR/payload/isp/$NAME"
    TARGET="/odm/lib64/camera/$NAME"
    chown root:root "$SOURCE"
    chmod 0644 "$SOURCE"
    chcon u:object_r:vendor_file:s0 "$SOURCE" 2>>"$LOG" || true
    if mount --bind "$SOURCE" "$TARGET" >>"$LOG" 2>&1; then
        log "isp bind ok $TARGET"
    else
        log "isp bind FAIL $TARGET"
    fi
done
for NAME in gamma_preview_sdr_conf.json gamma_quick_sdr_conf.json gamma_preview_hdr_conf.json; do
    bind_file "$MODDIR/payload/gamma/$NAME" "/odm/etc/camera/$NAME"
done
# Pro video ISZ (2x main, 6x tele; O-Log2 too): stock sets the ISO gate of rows 25-40 to 0/0,
# which DoIzoomStable() can never pass; -1 means "no ISO check", like the normal video rows.
# Patched from the live file on every boot. Touch $MODDIR/disable_pro_video_isz to skip.
HWCFG=/odm/etc/camera/CameraHWConfiguration.config
HWOUT=$MODDIR/merged/CameraHWConfiguration.config
if [ -f "$MODDIR/disable_pro_video_isz" ]; then
    log "pro video isz disabled"
elif [ -f "$HWCFG" ]; then
    mkdir -p "$MODDIR/merged"
    awk -F';' -v OFS=';' '
        /^\[/ { section = $0 }
        section == "[InsensorZoom]" && match($0, /^[ \t]*iZoomSettins\[[0-9]+\]/) {
            row = $0; sub(/^[ \t]*iZoomSettins\[/, "", row); sub(/\].*/, "", row)
            if (row + 0 >= 25 && row + 0 <= 40 && NF == 14 && $12 + 0 == 0 && $13 + 0 == 0 && $12 ~ /^[ \t]*0[ \t]*$/ && $13 ~ /^[ \t]*0[ \t]*$/) {
                $12 = "     -1"; $13 = "     -1"; patched++
            }
        }
        { print }
        END { print patched + 0 > "/dev/stderr" }
    ' "$HWCFG" > "$HWOUT" 2> "$MODDIR/merged/isz_count"
    COUNT=$(cat "$MODDIR/merged/isz_count" 2>/dev/null)
    if [ "$COUNT" = "16" ]; then
        bind_file "$HWOUT" "$HWCFG"
    else
        log "pro video isz skipped, patched rows=$COUNT (expected 16)"
    fi
fi
log "end"
