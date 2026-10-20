#!/system/bin/sh
# =============================================================================
# x8u-config-revert.sh — 撤掉模块对相机配置文件的注入，并解除 ColorOS 的启动拦截
#
# ---------------------------------------------------------------------------
# 为什么相机打不开（2026-10-13 实机取证，证据链完整）
# ---------------------------------------------------------------------------
# 1) 崩溃（因）：com.oplus.camera 7.006.123 解析 camera_unit_feature_config.protobuf
#    时会按固定下标取 repeated string（= 顶层 field1）的第 314 个元素：
#        java.lang.IndexOutOfBoundsException: Index 314 out of bounds for length 296
#          at com.google.oplus.protobuf.LazyStringArrayList.get(LazyStringArrayList.java:97)
#          at com.oplus.ocs.camera.configure.CameraConfigure.initFeatureInfo(CameraConfigure.java:556)
#          at ... CameraConfigure.initCameraTypeFeatureTable(443)
#          at ... CameraConfigure.parseProtobufFeature(296)
#          at ... CameraConfigure.decodeConfigFileVersion1(189)
#          at ... CameraConfigure.parseFromProtobuf(119)
#    我们注入的那份配置是从一份**更早的 X8U odm.img**（10-11 解包）做的，顶层只有 296 条，
#    比设备需要的少 18 条。→ 相机每次真正启动都崩。
# 2) 拦截（果）：连续崩溃后 ColorOS 把它记为「不稳定应用」，
#    之后在创建进程之前就拒绝启动：
#        W/OplusAppStartupManager: prevent start by classifyResrict com.oplus.camera,
#            cmp com.oplus.camera.Camera by activity  callingUid 2000
#        D/OplusAppStartupMonitor: notifyUnstableAppInfo: ... unstable_restrict_switch=true
#    记录落在 com.oplus.battery 的 Room 数据库 record.db 表 unstable_record，
#    字段 restrict_begin_time != 0 表示「当前处于限制中」。
#    Battery.apk 里的解除入口是 derestrictUnstableApp，其日志文案为
#        "derestrictUnstableApp: the app has been updated, packageName:"  ← 应用被更新即解除
#    另有 "in restrict protect period, not set restrict" 说明存在「保护期」。
#
# ---------------------------------------------------------------------------
# 本脚本
# ---------------------------------------------------------------------------
#   (默认)   ：A 取证 → B 撤掉配置注入 → C 导出原厂配置 → D 复测
#   check    ：只跑 A，一个字节都不写
#   unblock  ：在默认流程之上再跑 E：按代价从低到高依次尝试解除启动限制
#                1) 发 PACKAGE_REPLACED 广播
#                2) 用设备自带的 APK 重新安装（触发 derestrictUnstableApp 的 updated 分支）
#                3) 清 com.oplus.battery 数据（先整目录备份）
#
# 安全边界（与 x8u-repair.sh 同一套承诺）：
#   * umount / mount 改的是挂载表（内核内存），不是文件系统内容，重启即复原；
#   * 只读写 /data 与 /sdcard 上的普通文件；
#   * 不打开任何 /dev/block/*，无 dd / flash / mkfs，从不 remount rw；
#   * 不碰 boot / super / vbmeta / 分区表；不 setenforce、不 insmod。
#   → 与「变砖」之间没有通路。
# =============================================================================

MODE="${1:-revert}"
CFG=/odm/etc/camera/config
OUT=/sdcard/x8u-pristine-config
FILES="camera_unit_config camera_unit_feature_config.protobuf"

UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" != "0" ]; then
    echo "=============================================================="
    echo " !! 当前不是 root（uid=${UID_NOW:-?}），本脚本需要 root。"
    echo "    请在临时 root shell 里执行（先 su，或用 rootapp 自带的终端）。"
    echo "=============================================================="
    echo " 已中止，未做任何修改。"
    exit 1
fi

say()  { echo "[cfg-revert] $*"; }

# 取某挂载点**最顶层**的那条 mountinfo（同一挂载点多行时最后一行最靠上）
mi_root()   { awk -v p="$1" '$5==p {r=$4} END{print r}' /proc/self/mountinfo; }
mi_id()     { awk -v p="$1" '$5==p {i=$1} END{print i}' /proc/self/mountinfo; }
mi_fstype() { awk -v p="$1" '$5==p {for(k=1;k<=NF;k++) if($k=="-") f=$(k+1)} END{print f}' /proc/self/mountinfo; }

dump_cfg() {
    for N in $FILES; do
        T="$CFG/$N"
        printf "  %-44s" "$N"
        if [ -f "$T" ]; then
            printf " %10s B  md5=%s\n" "$(stat -c %s "$T")" "$(md5sum "$T" | cut -d' ' -f1)"
        else
            printf " (不存在)\n"
        fi
        printf "      mount id=%s fstype=%s root=%s\n" "$(mi_id "$T")" "$(mi_fstype "$T")" "$(mi_root "$T")"
    done
}

LAST_BLK=0
LAST_PID=0
test_camera() {
    am force-stop com.oplus.camera >/dev/null 2>&1
    sleep 1
    logcat -c 2>/dev/null
    am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.oplus.camera >/dev/null 2>&1
    sleep 5
    LAST_BLK=$(logcat -d 2>/dev/null | grep -c "prevent start by classifyResrict")
    LAST_PID=$(ps -A -o PID,NAME 2>/dev/null | grep -c "com.oplus.camera")
    say "    结果: classifyResrict=$LAST_BLK  相机进程数=$LAST_PID"
    [ "$LAST_PID" -gt 0 ]
}

echo "=============================================================="
echo " A. 取证（只读）"
echo "=============================================================="
say "mode  : $MODE"
say "date  : $(date)"
say "uptime: $(cat /proc/uptime 2>/dev/null)"

echo
say "--- 当前配置（经 bind 看到的内容）---"
dump_cfg

echo
say "--- 现场复现一次启动，看是否被拦 ---"
logcat -c 2>/dev/null
am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.oplus.camera >/dev/null 2>&1
sleep 2
logcat -d 2>/dev/null | grep -oE "prevent start by classifyResrict [a-zA-Z0-9._]+" | sort -u
say "（上面为空 = 当前没有被拦）"

echo
say "--- 相机最近的崩溃（dropbox，最近 6 条）---"
dumpsys dropbox --file 2>/dev/null | grep -B1 "app_crash" | tail -6

if [ "$MODE" = "check" ]; then
    echo
    echo " check 模式：未做任何修改。"
    exit 0
fi

echo
echo "=============================================================="
echo " B. 撤掉配置注入（回到设备自带配置）"
echo "=============================================================="
for N in $FILES; do
    T="$CFG/$N"
    R=$(mi_root "$T")
    case "$R" in
        *ricoh_pop_x9u*|*payload_pkj110*)
            say "umount $T"
            say "  源: $R"
            umount "$T" 2>&1 | sed 's/^/      /'
            ;;
        "")
            say "跳过 $T：该挂载点当前没有 mount（已经是原厂文件）"
            ;;
        *)
            say "!! 跳过 $T：顶层 root=$R 不是本模块注入的，拒绝 umount（安全护栏）"
            ;;
    esac
done

echo
say "--- 回滚后 ---"
dump_cfg

echo
echo "=============================================================="
echo " C. 导出原厂配置（供重新生成移植用）"
echo "=============================================================="
mkdir -p "$OUT" 2>/dev/null
for N in $FILES; do
    if cp -f "$CFG/$N" "$OUT/$N" 2>/dev/null; then
        say "导出 $OUT/$N  ($(stat -c %s "$OUT/$N") B)"
    else
        say "!! 导出失败 $N"
    fi
done
ls -la "$OUT" 2>&1

echo
echo "=============================================================="
echo " D. 复测"
echo "=============================================================="
say "force-stop 后连试 3 次..."
i=1
while [ "$i" -le 3 ]; do
    say "  第 ${i} 次:"
    if test_camera; then
        say "  → 相机进程起来了！请直接看取景画面。"
        break
    fi
    i=$((i + 1))
done

if [ "$LAST_PID" -le 0 ]; then
    say "相机仍未启动。这是预期的——限制记录还在（见 E 段）。"
fi

if [ "$MODE" = "unblock" ]; then
    echo
    echo "=============================================================="
    echo " E. 解除「不稳定应用」启动限制（代价从低到高）"
    echo "=============================================================="

    if [ "$LAST_PID" -gt 0 ]; then
        say "相机已能启动，跳过解除。"
    else
        say "[1/3] 发 PACKAGE_REPLACED 广播..."
        am broadcast -a android.intent.action.PACKAGE_REPLACED -d package:com.oplus.camera --user 0 2>&1 | head -3 | sed 's/^/      /'
        sleep 2
        say "  复测:"
        test_camera || true

        if [ "$LAST_PID" -le 0 ]; then
            say "[2/3] 重新安装设备自带的相机 APK（触发 derestrictUnstableApp 的 updated 分支）..."
            SRC=$(ls /product/app/OplusCamera/OplusCamera.apk /system*/app/OplusCamera/OplusCamera.apk 2>/dev/null | head -1)
            if [ -n "$SRC" ]; then
                say "  源 APK: $SRC ($(stat -c %s "$SRC") B)"
                cp -f "$SRC" /sdcard/OplusCamera-stock.apk 2>/dev/null \
                    && say "  已复制到 /sdcard/OplusCamera-stock.apk"
                pm install -r -d /sdcard/OplusCamera-stock.apk 2>&1 | sed 's/^/      /'
                sleep 3
                say "  复测:"
                test_camera || true
            else
                say "  !! 找不到 OplusCamera.apk，跳过"
            fi
        fi

        if [ "$LAST_PID" -le 0 ]; then
            say "[3/3] 清 com.oplus.battery 数据（记录就存在它的 record.db 里）..."
            DIR=/sdcard/x8u-startup-db
            BK=$DIR/FULLBATTERYBACKUP.tar.gz
            mkdir -p "$DIR" 2>/dev/null
            say "  整目录备份中（可回滚）..."
            tar -czf "$BK" -C /data/data com.oplus.battery -C /data/user_de/0 com.oplus.battery 2>/dev/null
            if [ -s "$BK" ]; then
                say "  备份 OK: $BK ($(stat -c %s "$BK") B)"
                pm clear com.oplus.battery 2>&1 | sed 's/^/      /'
                sleep 3
                say "  复测:"
                test_camera || true
            else
                say "  !! 备份失败，已中止（未执行清理）"
            fi
        fi
    fi
fi

echo
echo "=============================================================="
say "done。全程未写入任何块设备 / 未改动任何分区。"
if [ "$LAST_PID" -le 0 ]; then
    echo " 相机仍被拦时，还有一条零风险的手动路径："
    echo "   设置 → 电池 → 应用启动管理 → 右上角「启动记录 / 被阻止的应用」"
    echo "   → 找到「相机」→ 解除；或直接把相机的自动启动设为「允许」。"
    echo " 之后把上面完整输出发回（重点：B 段的 umount 结果、D/E 段的三个计数）。"
fi
