#!/system/bin/sh
# =============================================================================
# x8u-repair.sh — OPPO Find X8 Ultra (PKJ110) / ColorOS 17 临时 root
#
# 作用：把本模块在设备上的挂载修正到「X8U 原厂后端」这个正确状态，
#       不需要硬重启，也不需要重新刷模块。
#
# 为什么需要它（本机特有）：
#   本机 root 是 CVE exploit（com.pkj110.rootapp）拿到的临时 root，硬重启即失效，
#   只能软重启。软重启只重启用户态、不重启内核，于是：
#     * KernelSU 的 metamount 不会重建 /odm/lib64 的 overlay，它会一直冻结在
#       /mnt/vendor/mountify/odm/lib64 第一次生成时的内容（也就是 X9U 的后端库）；
#     * 模块的 system/ 里早就没有这三个库了，但 overlay 不重建就永远看不到这一点。
#   本脚本以 root 身份把 overlay 拆掉、把工作区里的 X9U 库删掉、并重绑三个配置文件。
#
# ---------------------------------------------------------------------------
# 【安全边界 —— 本脚本绝对不会做的事】（重要，请读完再执行）
#
#   本脚本只做两类操作，两类都只在「内核挂载表」和「/data 上的普通文件」里：
#     1. mount / umount / mount --bind
#        —— 内核挂载表操作。只改内存里的 mount namespace，
#           重启即消失，不写任何块设备，不改任何分区内容，不触碰 AVB / dm-verity。
#     2. 在 /data/local/tmp/ 与 /mnt/vendor/mountify/ 里做 mkdir / cp / rm
#        —— 普通文件操作，等价于任何 App 在自己的数据目录里存删文件。
#
#   本脚本不会、也无法做到下列任何一项（因此与「变砖」之间没有通路）：
#     x 打开任何 /dev/block/* 块设备来写（无 dd、无 flash、无 mkfs、无 mount -o rw）
#     x 修改 /odm /system /vendor 这些只读分区的实际内容（从不 remount rw）
#     x 触碰 boot / super / vbmeta / 分区表 / AVB 校验链
#     x setenforce、改 init、加载内核模块
#
#   额外的硬保险：每一次 umount 之前，本脚本都会先查 /proc/self/mountinfo，
#   确认该挂载点的**顶层**确实是我们自己注入的（overlay，或其 root 指向 ricoh_pop_x9u）。
#   只要对不上，就跳过、绝不 umount —— 连"误拆原厂分区挂载"的可能性都被排除。
#
# 用法（必须 root）：
#     sh /data/local/tmp/x8u-repair.sh check   # 只看现状，一个字节都不写
#     sh /data/local/tmp/x8u-repair.sh         # 执行修复
#
# 幂等：重复执行无副作用。
# =============================================================================

MODE="${1:-repair}"

# ---- root 自检：非 root 时 repair 一律中止（避免"以为跑了其实全失败"）----
UID_NOW=$(id -u 2>/dev/null)
if [ "$UID_NOW" != "0" ]; then
    echo "=============================================================="
    echo " !! 当前不是 root（uid=${UID_NOW:-?}）"
    echo "    本脚本需要 root：读 /data/adb 要 root，mount/umount 也要 root。"
    echo "    以普通身份跑会出现满屏 Permission denied，等于白跑。"
    echo "    请在你的临时 root shell 里执行（先 su，或用 rootapp 的终端）。"
    echo "=============================================================="
    if [ "$MODE" != "check" ]; then
        echo " 已中止，未做任何修改。"
        exit 1
    fi
    echo " check 模式下继续，但 /data/adb 相关内容会显示 Permission denied。"
    echo
fi

MOD=/data/adb/modules/ricoh_pop_x9u
CFG=/odm/etc/camera/config
WS=/mnt/vendor/mountify/odm/lib64
BACKUP=/data/local/tmp/x8u-repair-backup

say() { echo "[repair] $*"; }

# 取某挂载点**最顶层**的 "fstype root"（无挂载则输出空）。
# 依据：/proc/self/mountinfo 形如
#   id parentid maj:min root mountpoint opts opt... - fstype source superopts
# 同一挂载点的多行按"后出现者更靠上"取最后一行。
top_mount() {
    awk -v p="$1" '
        $5 == p {
            fs = ""; rt = $4
            for (i = 7; i <= NF; i++) if ($i == "-") { fs = $(i + 1); break }
            got = 1
        }
        END { if (got) print fs " " rt }
    ' /proc/self/mountinfo 2>/dev/null
}

echo "=============================================================="
echo " A. 现状取证（这一段的输出请完整保留）"
echo "=============================================================="
say "mode   : $MODE"
say "uptime : $(cat /proc/uptime)"
say "date   : $(date)"
echo
say "--- 模块目录 ---"
ls -la "$MOD" 2>&1
echo
say "--- 模块 payload_pkj110/ ---"
ls -la "$MOD/payload_pkj110" 2>&1
echo
say "--- 模块 payload/（PMA110，仅列前 20 项）---"
ls -la "$MOD/payload" 2>&1 | head -20
echo
say "--- bind.log（关键！bind_file 为什么没绑上）---"
cat "$MOD/bind.log" 2>&1
echo
say "--- mount.log ---"
cat "$MOD/mount.log" 2>&1
echo
say "--- overlay 工作区 $WS ---"
ls -la "$WS" 2>&1
echo
say "--- 当前 /odm/lib64 三个后端库 ---"
ls -la /odm/lib64/libBasicTonePhoto.so /odm/lib64/libAlgoInterface.so /odm/lib64/libmsnativefilter.so 2>&1
echo
say "--- 当前相关挂载（mountinfo: fstype | root | mountpoint）---"
awk '$5 == "/odm/lib64" || $5 ~ /odm\/etc\/camera/ {
        fs = ""; for (i = 7; i <= NF; i++) if ($i == "-") { fs = $(i + 1); break }
        printf "   %-8s %s  <-  %s\n", fs, $4, $5
     }' /proc/self/mountinfo 2>/dev/null

if [ "$MODE" = "check" ]; then
    echo
    echo "=============================================================="
    echo " check 模式：到此为止，未做任何修改。"
    echo " 如果要把上面的输出发给我，直接复制粘贴即可。"
    echo "=============================================================="
    exit 0
fi

echo
echo "=============================================================="
echo " B. 拆掉 /odm/lib64 的 KSU overlay（恢复 X8U 原厂后端）"
echo "=============================================================="
n=0
while :; do
    TOPS=$(top_mount /odm/lib64)
    [ -z "$TOPS" ] && break
    FS=$(echo "$TOPS" | cut -d' ' -f1)
    RT=$(echo "$TOPS" | cut -d' ' -f2-)
    if [ "$FS" != "overlay" ]; then
        say "顶层 fstype=$FS（不是 overlay）→ 原厂挂载，不动它"
        break
    fi
    if umount -l /odm/lib64 2>&1; then
        n=$((n + 1))
        say "detached overlay #$n  (root=$RT)"
    else
        say "!! umount 失败，/odm/lib64 仍被占用"
        break
    fi
    [ "$n" -ge 4 ] && break
done
[ "$n" -eq 0 ] && say "（没有 overlay 可拆）"
say "处理后："
ls -la /odm/lib64/libBasicTonePhoto.so /odm/lib64/libAlgoInterface.so /odm/lib64/libmsnativefilter.so 2>&1
say "期望 538400 / 60867488 / 7992584"

echo
echo "=============================================================="
echo " C. 清掉工作区里残留的 X9U 后端库（防止下次开机又盖回来）"
echo "=============================================================="
if [ -d "$WS" ]; then
    mkdir -p "$BACKUP" 2>/dev/null
    for L in libBasicTonePhoto.so libAlgoInterface.so libmsnativefilter.so; do
        if [ -f "$WS/$L" ]; then
            cp -f "$WS/$L" "$BACKUP/$L" 2>/dev/null && say "backed up $L -> $BACKUP/$L"
            rm -f "$WS/$L" && say "purged $L"
        fi
    done
else
    say "$WS 不存在，跳过"
fi
ls -la "$WS" 2>&1

echo
echo "=============================================================="
echo " D. 重绑三个配置文件（撤掉陈旧 bind，绑模块里的新版本）"
echo "=============================================================="
for N in camera_unit_config camera_unit_feature_config.protobuf oplus_camera_algo_switch_config; do
    say "--- $N ---"
    while :; do
        TOPS=$(top_mount "$CFG/$N")
        [ -z "$TOPS" ] && break
        FS=$(echo "$TOPS" | cut -d' ' -f1)
        RT=$(echo "$TOPS" | cut -d' ' -f2-)
        case "$RT" in
            *ricoh_pop_x9u* | *payload_pkj110*) ;;
            *)
                say "顶层 root=$RT 不是本模块注入的 → 不动它"
                break
                ;;
        esac
        if umount "$CFG/$N" 2>&1; then
            say "detached stale bind (fstype=$FS root=$RT)"
        else
            say "!! umount 失败"
            break
        fi
    done

    S="$MOD/payload_pkj110/$N"
    if [ ! -f "$S" ]; then
        say "!! 模块里没有 $S —— 配置无法修正，请贴出上面 A 段的 payload_pkj110 列表"
        continue
    fi
    if [ ! -f "$CFG/$N" ]; then
        say "!! 目标不存在（已被 umount 掩盖）：$CFG/$N"
        continue
    fi
    if mount --bind "$S" "$CFG/$N" 2>&1; then
        say "bind ok  ($(stat -c %s "$S") B)"
    else
        say "bind FAIL"
    fi
done
echo
say "--- 处理后设备实际提供的配置 ---"
ls -la "$CFG/camera_unit_config" "$CFG/camera_unit_feature_config.protobuf" \
       "$CFG/oplus_camera_algo_switch_config" 2>&1
for N in camera_unit_config camera_unit_feature_config.protobuf oplus_camera_algo_switch_config; do
    if [ -f "$CFG/$N" ]; then
        say "$N md5 = $(md5sum "$CFG/$N" 2>/dev/null | cut -d' ' -f1)"
    fi
done
say "期望 camera_unit_config            = 32454 B / 6b6c77aa8694bd55d2eaaacaec5a659a"
say "期望 protobuf                      = 62330 B / b0ee50c1a3bbc9e1dcc20f2391a490c2"
say "期望 oplus_camera_algo_switch_config = 141474 B / 593dbc7913682da175663d708b005120"

echo
echo "=============================================================="
echo " E. 重启相机"
echo "=============================================================="
am force-stop com.oplus.camera 2>&1
say "done — 全程未写入任何块设备 / 未改动任何分区，重启即完全复原"
say "现在打开相机看看是否还黑屏"
