#!/system/bin/sh
# POP 仍不正常时的诊断取证包 —— 设备侧执行（root）。
#
#   sh x8u-pop-diag.sh            # 采集，落盘到 /sdcard/omnicam-diag-<ts>/
#   sh x8u-pop-diag.sh pack       # 采集后打成 tar.gz，便于 adb pull
#
# 采集内容围绕 §4.7 的证据链：配置形状 → 用例表 → 建会话 → 配流 → 帧。
# 全部只读，不改任何配置。
set -u

TS=$(date +%Y%m%d-%H%M%S)
OUT=/sdcard/omnicam-diag-$TS
DST=/odm/etc/camera/config
MOD=/data/adb/modules/ricoh_pop_x9u
LOG=/data/local/tmp/omnicam-diag.log
mkdir -p "$OUT"
: > "$LOG"

uid=$(id -u 2>/dev/null || echo 1)
[ "$uid" = 0 ] || { echo "[diag] 需要 root（uid=$uid）"; exit 1; }

run() {  # run <文件名> <命令…>  —— 直接执行命令（无 shell 片段时用）
    NAME=$1; shift
    { echo "### $*"; "$@" 2>&1; } > "$OUT/$NAME" 2>>"$LOG" || true
    echo "[diag] $NAME  $(wc -l < "$OUT/$NAME" 2>/dev/null || echo 0) 行"
}

sect() {  # sect <文件名> <shell 片段>
    NAME=$1; shift
    { echo "### $*"; sh -c "$*" 2>&1; } > "$OUT/$NAME" 2>>"$LOG" || true
    echo "[diag] $NAME  $(wc -l < "$OUT/$NAME" 2>/dev/null || echo 0) 行"
}

echo "[diag] 输出目录 $OUT"

# ---------- 1. 配置层：两边 md5 + 绑定的真实来源 ----------
sect 01-mount.txt '
echo "== /odm/etc/camera 全部挂载 =="
grep " /odm/etc/camera" /proc/self/mountinfo
echo
echo "== 两个配置的 bind 详情（第 4 字段是 bind 源，带 //deleted 表示源 inode 已删） =="
for F in camera_unit_config camera_unit_feature_config.protobuf; do
    echo "-- $F"
    grep " /odm/etc/camera/config/$F " /proc/self/mountinfo || echo "   (无 bind，读分区原厂文件)"
done
echo
echo "== 大小与 md5 =="
for F in camera_unit_config camera_unit_feature_config.protobuf; do
    echo "-- $F"
    echo "   live    $(stat -c "%s B %y" /odm/etc/camera/config/$F 2>&1)  $(md5sum /odm/etc/camera/config/$F 2>&1)"
    echo "   payload $(stat -c "%s B %y" '"$MOD"'/payload_pkj110/$F 2>&1)  $(md5sum '"$MOD"'/payload_pkj110/$F 2>&1)"
done
echo
echo "== 机型 =="
getprop | grep -iE "product.(model|device|name)|build.display.id" | head -20
'

# ---------- 2. 配置形状：pair-list 还在不在 ----------
sect 02-shape.txt '
F=/odm/etc/camera/config/camera_unit_config
echo "== usecase_info 里三个关键键的原始片段 =="
for K in gr_mode_case gr_mode_hq_raw_case retro_camera_case; do
    echo "-- $K"
    grep -o "\"$K\":[^]]*]" $F | head -c 400
    echo
done
echo
echo "== 变形指纹：[[\" ... （原生配置里出现 0 次） =="
grep -c "\[\[" $F
echo "== retro_camera_case 是否可见 =="
grep -c "retro_camera_case" $F
'

# ---------- 3. 模块脚本与日志 ----------
sect 03-module.txt '
echo "== 模块目录 =="
ls -la '"$MOD"' 2>&1
echo
echo "== post-fs-data.sh 指纹（应为 11009 B / e315109c…） =="
stat -c "%s B %y" '"$MOD"'/post-fs-data.sh 2>&1
md5sum '"$MOD"'/post-fs-data.sh 2>&1
echo
echo "== bind.log =="
cat '"$MOD"'/bind.log 2>&1 | tail -80
echo
echo "== mount.log =="
cat '"$MOD"'/mount.log 2>&1 | tail -40
'

# ---------- 4. LSPosed 侧 = ----------
sect 04-lsposed.txt '
for P in /data/adb/lspd/log/modules.log /data/adb/lspd/log/verbose/modules.log /data/adb/lsposed/log/modules.log; do
    [ -f "$P" ] || continue
    echo "== $P ($(stat -c %s "$P") B) =="
    grep -aE "PopPort|CameraSessionProbe|GrPort|RicohGrPort|Aura|aura_native|unavailable" "$P" | tail -120
    echo
done
echo "== 抓一次「这一轮」的日志起点提示 =="
echo "（LSPosed 在进程启动时加载模块代码；改 APK 后 am force-stop 即生效，不需要软重启）"
'

# ---------- 5. 异常与崩溃 ----------
sect 05-crash.txt '
echo "== dropbox 里相机相关的最近条目 =="
dumpsys dropbox --print 2>/dev/null | grep -aE "FATAL EXCEPTION|Caused by|getUseCaseValues|usecase_info|STREAM_SURFACE_ERROR|IndexOutOfBounds|NullPointerException" | tail -60
'

# ---------- 6. 会话 / 配流 / 帧 ----------
echo "[diag] 线程转储：先重启相机再抓，避免抓到空闲态"
am force-stop com.oplus.camera >/dev/null 2>&1
sleep 1
monkey -p com.oplus.camera -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
    || am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.oplus.camera >/dev/null 2>&1
echo "[diag] 已拉起相机。请**在 20 秒内切到 POP 界面**，脚本随后自动采样。"
sleep 20

sect 06-camera-log.txt '
logcat -d -v threadtime 2>/dev/null | grep -aE \
"CameraUnit|Camera2StateMachineImpl|Camera2Impl|CameraSessionEntity|createNewSession|configure_streams|configureStreamsLocked|ILLEGAL_ARGUMENT|not supported by HAL|STREAM_SURFACE_ERROR|PREVIEW_CONFIG_FAILED|getUseCaseValues|usecase_info|OCAM_|PreviewRenderer|CameraPreviewUI|GLThread|APS_ALOG|camx|AidlCamera3-Device|onSessionConfigureFail|deferred job|DeferJob|PopPort|ApsProcessor|APSDeferJobGoverner|libc|Abort message" \
| tail -400
'

sect 07-session-request.txt '
logcat -d -v threadtime 2>/dev/null | grep -aE "session request|sessionType=|outputs\(" | tail -80
echo
echo "（上面若为 0 行：说明应用在 createNewSession 阶段就抛异常了，根本没调 createCaptureSession —— 即 §4.7 的形状问题仍在）"
'

sect 08-dumpsys-camera.txt '
dumpsys media.camera 2>/dev/null | sed -n "1,160p"
'

sect 09-ps.txt '
echo "== 相机进程 =="
ps -A -o USER,PID,PPID,NAME 2>/dev/null | grep -E "camera|CAMERA" 
echo
echo "== 相机进程线程（看 PreviewGLThread / APS） =="
PID=$(pidof com.oplus.camera 2>/dev/null | cut -d" " -f1)
[ -n "$PID" ] && { echo "pid=$PID"; ls /proc/$PID/task 2>/dev/null | head -5; }
'

sect 10-threaddump.txt '
PID=$(pidof com.oplus.camera 2>/dev/null | cut -d" " -f1)
[ -n "$PID" ] || { echo "相机进程不存在"; exit 0; }
echo "== pid=$PID =="
kill -3 "$PID" 2>/dev/null
sleep 2
logcat -d -v threadtime 2>/dev/null | grep -aA 400 "----- pid $PID" | tail -450
'

sect 11-focus.txt '
echo "== 当前前台 =="
dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | head -5
dumpsys activity activities 2>/dev/null | grep -E "ResumedActivity" | head -5
'

# ---------- 7. 原生库：确认 GR 原生 hook 的指纹能命中 ----------
sect 12-native.txt '
echo "== /odm/lib64 下的后端库（相机 7.006.94 vs 7.006.123 与它们无关） =="
ls -la /odm/lib64/libAlgoInterface.so /odm/lib64/libAlgoProcess.so /odm/lib64/libBasicTonePhoto.so 2>&1
echo
echo "== md5（预期 libAlgoInterface.so = 28715a07bac87e3c2d286c9db2ca8f7c，"
echo "        与本地 x8u_extract/odm_lib64/ 一致 ⇒ gr_basictone.cpp 的 getSettingInt 指纹会命中） =="
md5sum /odm/lib64/libAlgoInterface.so /odm/lib64/libAlgoProcess.so /odm/lib64/libBasicTonePhoto.so 2>&1
echo
echo "== 是否有 bind overlay 盖在这些库上（应为空） =="
grep -E " /odm/lib64/(libAlgoInterface|libAlgoProcess|libBasicTonePhoto|libmsnativefilter)\.so " /proc/self/mountinfo || echo "   (无 overlay，用的是固件原件)"
echo
echo "== getSettingInt 序言（偏移 0x13f46d4，预期 3f2303d5 ff4302d1 fd7b06a9 f65707a9） =="
dd if=/odm/lib64/libAlgoInterface.so bs=1 skip=$((0x13f46d4)) count=16 2>/dev/null | od -An -tx1
'

echo "[diag] 采集完成：$OUT"
echo "[diag] 下一步：先在手机上**手动切到 POP**，确认画面是否实时动；"
echo "[diag] 然后 adb pull $OUT"

if [ "${1:-}" = pack ]; then
    TAR=/sdcard/omnicam-diag-$TS.tar.gz
    tar -czf "$TAR" -C /sdcard "omnicam-diag-$TS" 2>>"$LOG" && echo "[diag] 打包：$TAR"
fi
