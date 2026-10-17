#!/system/bin/sh
# POP(复古相机) 浓郁/Vibrant 滤镜修复 —— 设备侧执行。
#
# 病因见 port-x8u/README.md §4.8：
#   POP 的「浓郁」= camera_filter_retro_vibrant_dsc（英文名 Positive / 中文 hint「正片 胶片」），
#   走 FilterConstant.STYLE_FILTER_TYPE_POSITIVE_HDR_PATH 等 5 个常量，最终作为
#   IPU_BaseProcessUnitSDK.setProcessParameter("filter_type", …) 的**文件名**交给 meicam。
#   meicam 去 /odm/etc/camera/meishe_lut/<name> 打开；而 X8U 的 ODM 里
#   **整个 positive_* 家族都不存在**（出厂 F.21 odm.img 的 meishe_lut 共 111 个条目，
#   逐个核对无一条 positive_*），于是：
#       E meicam: Failed to open lut file /odm/etc/camera/meishe_lut/positive_hdr_normal_a_1.bin
#       E meicam: Unkown lut file  /odm/etc/camera/meishe_lut/positive_hdr_normal_a_1.bin
#   色卡不生效 ⇒ 界面看得到「浓郁」，选了没反应。
#   （摄影模式那套 positive 也是同样缺文件；这两个家族是 X9/X10 的 ODM 资源，
#     本机 ODM 只给了同族的 portra400_* / blackandwhite_* 更新版，没给 positive_*。）
#
# 本机 ODM 里语义等价、且**确实存在**的「正片」色卡是 gr.posi.rgba.bin（24,128 B，
# .MS-LUT 格式，与 meishe_lut 里其它色卡同一容器：magic ".MS-LUT " + dim³ 三维表）。
# 本脚本把它的字节按 8 个 positive_* 名字补进 meishe_lut，幂等、可反复跑：
#   ① 校验模块 payload 里 8 份替身（md5/size 必须一致，否则中止）
#   ② 写进 $TARGET 当前 bind 的源目录（post-fs-data.sh 的合并树），并打上
#      u:object_r:vendor_configs_file:s0 —— 与目录内其它色卡一致，相机才读得到
#   ③ 复核 /odm 侧真的能按名字读到、md5 一致
#
#   sh x8u-fix-vibrant.sh check    # 只读取证
#   sh x8u-fix-vibrant.sh apply    # 补齐 8 份色卡 + 复核 + 重启相机
#   sh x8u-fix-vibrant.sh revert   # 撤掉这 8 份（回到"选了没反应"）
#
# 模块 payload（$PAYLOAD）是权威源；host 侧 apply-vibrant-lut.sh 可先把它们推上来。
set -u

STAGE=/data/local/tmp/x8u-fixlut
MOD=/data/adb/modules/ricoh_pop_x9u
PAYLOAD=$MOD/payload/meishe_lut
MERGED_ROOT=/data/adb/ricoh_pop_x9u/merged
TARGET=/odm/etc/camera/meishe_lut
LOG=/data/local/tmp/x8u-fix-vibrant.log

# 替身来源（本机 ODM 自带的「正片」色卡），仅用于 check 时对照说明。
REF_NAME=gr.posi.rgba.bin
MD_LUT=ec7eee635f70dbf299d6c49e750739b7
SZ_LUT=24128
LUT_CTX=u:object_r:vendor_configs_file:s0

# APK 里会出现的全部 positive 名字（FilterConstant 的 5 个常量展开）。
FILES="positive_normal.bin positive_master.bin positive_sdr_gen_a_1.bin positive_sdr_gen_d_1.bin positive_hdr_normal_a_1.bin positive_hdr_normal_d_1.bin positive_hdr_master_a_1.bin positive_hdr_master_d_1.bin"

uid=$(id -u 2>/dev/null || echo 1)
[ "$uid" = 0 ] || { echo "[vib] 需要 root：用 su -c 或 root shell 运行（当前 uid=$uid）"; exit 1; }

# 源目录：host 侧推到 $STAGE；没有 PC 时退化为"用模块自带的 payload 当源"。
if [ ! -f "$STAGE/positive_normal.bin" ] && [ -f "$PAYLOAD/positive_normal.bin" ]; then
    STAGE=$PAYLOAD
fi

md5of() { md5sum "$1" 2>/dev/null | cut -d' ' -f1; }
sizeof() { stat -c %s "$1" 2>/dev/null || echo '?'; }

# $TARGET 当前 bind 的源目录。mountinfo 第 4 字段是「在其所在文件系统内的相对根」，
# 而 /data 的 root 就是 "/"，所以设备路径 = /data + 该字段。
merged_dir() {
    R=$(grep " $TARGET " /proc/self/mountinfo 2>/dev/null | head -1 | awk '{print $4}')
    if [ -n "$R" ]; then
        case "$R" in
            /*) if [ -d "/data$R" ]; then echo "/data$R"; return 0; fi ;;
        esac
    fi
    # 兜底：post-fs-data.sh 在 unmount 不干净时会退化成 "$ROOT/$NAME.$$"
    D=$(ls -dt "$MERGED_ROOT"/meishe_lut* 2>/dev/null | head -1)
    [ -n "$D" ] && { echo "$D"; return 0; }
    return 1
}

do_check() {
    echo "[vib] ===== 取证 ====="
    echo "[vib] 机型 ro.product.model=$(getprop ro.product.model)  ro.product.device=$(getprop ro.product.device)"
    case "$(getprop ro.product.model)" in
        *PKJ110*) ;;
        *) echo "[vib] 注意：本脚本按 Find X8 Ultra (PKJ110) 的期望 md5 校验，当前机型不是 PKJ110" ;;
    esac

    D=$(merged_dir)
    echo "[vib] 合并树 bind 源：${D:-<未找到>}"
    echo "[vib] /odm/etc/camera 下与 meishe_lut 相关的挂载："
    grep " $TARGET" /proc/self/mountinfo 2>/dev/null | awk '{print "  [vib]   "$5"  <-  /data"$4}' \
        || echo "  [vib]   (无 bind：读的是分区原厂目录，post-fs-data.sh 没跑到)"

    echo "[vib] --- 替身对照 ---"
    echo "[vib]   $REF_NAME  ($TARGET)  $(sizeof "$TARGET/$REF_NAME") B  md5=$(md5of "$TARGET/$REF_NAME")"
    echo "[vib]   8 份 positive 替身应为 ${SZ_LUT} B / md5=$MD_LUT"

    echo "[vib] --- 源 / payload / live 三处状态 ---"
    for F in $FILES; do
        printf '%s\n' "[vib]   $F"
        printf '%s\n' "[vib]     源 $STAGE   $(sizeof "$STAGE/$F") B  md5=$(md5of "$STAGE/$F")"
        printf '%s\n' "[vib]     payload     $(sizeof "$PAYLOAD/$F") B  md5=$(md5of "$PAYLOAD/$F")"
        printf '%s\n' "[vib]     live(omd)   $(sizeof "$TARGET/$F") B  md5=$(md5of "$TARGET/$F")"
    done
    echo "[vib] 日志：$LOG"
}

do_verify() {
    rc=0
    echo "[vib] ===== 复核 ====="
    for F in $FILES; do
        A=$(md5of "$TARGET/$F")
        if [ "$A" = "$MD_LUT" ]; then
            echo "[vib]   live $F  ok"
        else
            echo "[vib]   live $F  **FAIL** md5=${A:-缺失} 期望=$MD_LUT"; rc=1
        fi
    done
    [ "$rc" = 0 ] && echo "[vib] 8 份全部就位" || echo "[vib] 有失败项（见上）"
    return $rc
}

do_apply() {
    : > "$LOG"

    for F in $FILES; do
        [ -f "$STAGE/$F" ] || { echo "[vib] 中止：源缺失 $STAGE/$F（先跑 host 侧 apply-vibrant-lut.sh）"; return 1; }
        S=$(sizeof "$STAGE/$F"); M=$(md5of "$STAGE/$F")
        [ "$S" = "$SZ_LUT" ] || { echo "[vib] 中止：源大小不符 $F=$S，期望 $SZ_LUT"; return 1; }
        [ "$M" = "$MD_LUT" ] || { echo "[vib] 中止：源 md5 不符 $F=$M，期望 $MD_LUT"; return 1; }
    done

    # 源不是模块 payload 时，先写回 payload —— 与 post-fs-data.sh 同源，
    # 「现在生效」与「下次开机仍生效」就是同一份字节。
    if [ "$STAGE" != "$PAYLOAD" ]; then
        if [ -d "$PAYLOAD" ]; then
            for F in $FILES; do
                if ! cp -f "$STAGE/$F" "$PAYLOAD/$F" >>"$LOG" 2>&1; then
                    echo "[vib] 中止：写 payload 失败 $PAYLOAD/$F（见 $LOG）"; return 1
                fi
                chown root:root "$PAYLOAD/$F" 2>>"$LOG"
                chmod 0644 "$PAYLOAD/$F" 2>>"$LOG"
            done
            echo "[vib] 模块 payload 已补齐 8 份色卡"
        else
            echo "[vib] 警告：$PAYLOAD 不存在，本次只改合并树；重启后会回退"
        fi
    fi

    D=$(merged_dir)
    if [ -z "$D" ]; then
        echo "[vib] 中止：找不到 meishe_lut 的合并树源目录。"
        echo "[vib]      说明这次开机 post-fs-data.sh 没有合并 meishe_lut ——"
        echo "[vib]      此时 /odm/etc/camera/meishe_lut 是只读原厂目录，只能改 payload 后重启。"
        return 1
    fi
    echo "[vib] 合并树源：$D"

    for F in $FILES; do
        if ! cp -f "$STAGE/$F" "$D/$F" >>"$LOG" 2>&1; then
            echo "[vib] cp FAIL $F（见 $LOG）"; continue
        fi
        chown root:root "$D/$F" 2>>"$LOG"
        chmod 0644 "$D/$F" 2>>"$LOG"
        # 与目录内其它色卡同标签，否则相机（untrusted_app）读不到。
        chcon "$LUT_CTX" "$D/$F" 2>>"$LOG"
        echo "[vib] ok   $F  -> $D"
    done

    sync
    do_verify
}

do_revert() {
    D=$(merged_dir)
    if [ -z "$D" ]; then echo "[vib] 找不到合并树源目录，无需处理"; return 0; fi
    n=0
    for F in $FILES; do
        if [ -f "$D/$F" ]; then
            rm -f "$D/$F" && { echo "[vib] 已移除 $D/$F"; n=$((n + 1)); }
        fi
    done
    echo "[vib] 共移除 $n 份。注意：$PAYLOAD 里仍有这 8 份，下次开机 post-fs-data.sh 会再补回来。"
    echo "[vib] 若要连 payload 一起去掉，在 PC 上从模块包里删掉这 8 个文件后重装模块。"
}

case "${1:-check}" in
    check) do_check ;;
    apply)
        if do_apply; then
            echo "[vib] 强制停止相机，下次启动即读到新色卡"
            am force-stop com.oplus.camera
            do_check
            echo "[vib] 完成。打开相机 → POP → 选「浓郁」，画面应立刻整体偏正片色（不再是原色）。"
        else
            echo "[vib] 应用失败，未改动相机。"
            exit 1
        fi
        ;;
    revert) do_revert ;;
    *) echo "用法: $0 {check|apply|revert}"; exit 2 ;;
esac
