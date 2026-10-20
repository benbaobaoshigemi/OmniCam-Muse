#!/system/bin/sh
# POP(复古相机)卡死修复 —— 设备侧执行。
#
# 病因见 port-x8u/README.md §4.7：payload_pkj110/camera_unit_config 里被
# build_x8u_port.py 写成了 pair-list（[[k,v],…] 而不是 {k:v}），
# com.oplus.ocs.camera.configure.JsonParser.parseUsecaseInfo() 解析到
# usecase_info/gr_mode_case 就抛异常并**返回半张表**，导致排在它后面的
# gr_mode_hq_raw_case / retro_camera_case 全部丢失，进 POP 时
# BaseMode.getUseCaseValues 抛 "retro_camera_case's usecase_info are not configured!"，
# 会话根本建不起来（相机是共享单例 ⇒ 切到任何模式都黑）。
#
# 本脚本做三件事，幂等，可反复跑：
#   ① 解开 $DST 上悬空的旧 bind（其源 inode 已 nlink=0，不解开就改不动真正被读的字节）
#   ② 把 $STAGE 下两份修好的配置写回模块 payload_pkj110/，**并从 payload 重新 bind**
#      —— 与 post-fs-data.sh 同源，所以「现在生效」和「下次开机仍生效」是同一份字节
#   ③ 复核 live 与 payload 的 md5，并检查 bind 源是否还指着 "//deleted" 的旧 inode
#
#   sh x8u-fix-pop.sh check     # 只读取证（源 / 目标 / payload / 挂载 / md5）
#   sh x8u-fix-pop.sh apply     # 绑定 + 写回 payload + 复核 + 重启相机
#   sh x8u-fix-pop.sh revert    # 撤回原厂配置（不动 payload）
#
# 源文件由 host 侧 apply-pop-fix.sh 推入 $STAGE。
set -u

STAGE=/data/local/tmp/x8u-fixcfg
DST=/odm/etc/camera/config
MOD=/data/adb/modules/ricoh_pop_x9u
PAYLOAD=$MOD/payload_pkj110
LOG=/data/local/tmp/x8u-fix-pop.log

# 期望值必须与 tools/verify_port_artifacts.py 里的常量一致。
MD_UNIT=6b6c77aa8694bd55d2eaaacaec5a659a
SZ_UNIT=32454
MD_PROTO=b0ee50c1a3bbc9e1dcc20f2391a490c2
SZ_PROTO=62330

FILES="camera_unit_config camera_unit_feature_config.protobuf"

uid=$(id -u 2>/dev/null || echo 1)
[ "$uid" = 0 ] || { echo "[pop] 需要 root：用 su -c 或 root shell 运行（当前 uid=$uid）"; exit 1; }

# 源目录：host 侧推到 $STAGE；没有 PC 时退化为"用模块自带的 payload 当源"，
# 这样在设备上直接 `su -c 'sh /data/adb/modules/ricoh_pop_x9u/x8u-fix-pop.sh apply'`
# 就能把模块里那份修好的配置真正绑上去。
if [ ! -f "$STAGE/camera_unit_config" ] && [ -f "$PAYLOAD/camera_unit_config" ]; then
    echo "[pop] $STAGE 无源，改用模块自带 payload：$PAYLOAD"
    STAGE=$PAYLOAD
fi

md5of() { md5sum "$1" 2>/dev/null | cut -d' ' -f1; }
sizeof() { stat -c %s "$1" 2>/dev/null || echo '?'; }

expect_md5() {
    case "$1" in
        camera_unit_config) echo "$MD_UNIT" ;;
        camera_unit_feature_config.protobuf) echo "$MD_PROTO" ;;
        *) echo '' ;;
    esac
}

expect_sz() {
    case "$1" in
        camera_unit_config) echo "$SZ_UNIT" ;;
        camera_unit_feature_config.protobuf) echo "$SZ_PROTO" ;;
        *) echo '?' ;;
    esac
}

# 该目标当前 bind 的 mountinfo 行（空 = 没有 bind，读的是分区原厂文件）
bind_line() {
    grep " $DST/$1 " /proc/self/mountinfo 2>/dev/null | head -1
}

# mountinfo 的第 4 字段是「文件系统内的相对根」，bind 的源路径就在这里，
# 源 inode 被删掉时它会以 "//deleted" 结尾 —— 那正是"磁盘上的 payload 已经
# 更新、但相机仍读旧字节"的原因。
bind_root() {
    bind_line "$1" | awk '{print $4}'
}

do_check() {
    echo "[pop] ===== 取证 ====="
    echo "[pop] 机型 ro.product.model=$(getprop ro.product.model)  ro.product.device=$(getprop ro.product.device)"
    case "$(getprop ro.product.model)" in
        *PKJ110*) ;;
        *) echo "[pop] 注意：本脚本按 Find X8 Ultra (PKJ110) 的期望 md5 校验，当前机型不是 PKJ110" ;;
    esac

    echo "[pop] --- 待写入源 ($STAGE) ---"
    for F in $FILES; do
        if [ -f "$STAGE/$F" ]; then
            M=$(md5of "$STAGE/$F")
            if [ "$M" = "$(expect_md5 "$F")" ]; then ST='ok'; else ST="**不匹配** 期望 $(expect_md5 "$F")"; fi
            echo "[pop]   $F  $(sizeof "$STAGE/$F") B  md5=$M  $ST"
        else
            echo "[pop]   $F  缺失 —— 请先由 host 侧 apply-pop-fix.sh 推入"
        fi
    done

    echo "[pop] --- 设备当前状态 ---"
    for F in $FILES; do
        echo "[pop]   live    $F  $(sizeof "$DST/$F") B  md5=$(md5of "$DST/$F")  期望 $(expect_sz "$F") B / $(expect_md5 "$F")"
        echo "[pop]   payload $F  $(sizeof "$PAYLOAD/$F") B  md5=$(md5of "$PAYLOAD/$F")"
        R=$(bind_root "$F")
        if [ -z "$R" ]; then
            echo "[pop]   bind    无 —— 当前读分区原厂文件"
        else
            case "$R" in
                *//deleted*) echo "[pop]   bind    源=已删除 inode  $R   ← 必须 apply 才能生效" ;;
                *)           echo "[pop]   bind    源=$R" ;;
            esac
        fi
    done

    echo "[pop] /odm/etc/camera 下的挂载："
    grep " /odm/etc/camera" /proc/self/mountinfo 2>/dev/null | awk '{print "  [pop]   "$5" <- "$4}' || echo "  [pop]   (无)"
    echo "[pop] 日志：$LOG"
}

do_verify() {
    rc=0
    echo "[pop] ===== 复核 ====="
    for F in $FILES; do
        E=$(expect_md5 "$F")
        A=$(md5of "$DST/$F")
        B=$(md5of "$PAYLOAD/$F")
        if [ "$A" = "$E" ]; then echo "[pop]   live    $F  ok"; else echo "[pop]   live    $F  **FAIL** md5=$A 期望=$E"; rc=1; fi
        if [ "$B" = "$E" ]; then echo "[pop]   payload $F  ok"; else echo "[pop]   payload $F  **FAIL** md5=$B 期望=$E"; rc=1; fi
        R=$(bind_root "$F")
        case "$R" in
            '')          echo "[pop]   bind    $F  **FAIL** 没有 bind，读的是原厂文件"; rc=1 ;;
            *//deleted*) echo "[pop]   bind    $F  **FAIL** 源仍是已删除 inode：$R"; rc=1 ;;
            *)           echo "[pop]   bind    $F  源=$R" ;;
        esac
    done
    [ "$rc" = 0 ] && echo "[pop] 全部通过" || echo "[pop] 有失败项（见上）"
    return $rc
}

do_apply() {
    : > "$LOG"
    for F in $FILES; do
        [ -f "$STAGE/$F" ] || { echo "[pop] 中止：源缺失 $STAGE/$F"; return 1; }
        M=$(md5of "$STAGE/$F")
        [ "$M" = "$(expect_md5 "$F")" ] || { echo "[pop] 中止：源 md5 不符 $F=$M，期望 $(expect_md5 "$F")"; return 1; }
    done

    # ① 先解开悬空的旧 bind。它 hold 的是已被替换（nlink=0）的旧 inode，
    #    不解开的话，下面的 cp 改不到相机真正在读的那份字节。
    for F in $FILES; do
        T="$DST/$F"
        n=0
        while grep -q " $T " /proc/self/mounts 2>/dev/null; do
            umount "$T" >>"$LOG" 2>&1 || break
            n=$((n + 1))
            [ "$n" -gt 5 ] && break
        done
    done

    # ② 把修好的字节写回模块 payload。之后就从 payload 绑 —— 与 post-fs-data.sh
    #    用的是同一个源，所以「现在生效」和「下次开机仍生效」是同一份字节；
    #    顺带避开 /data/local/tmp（shell_data_file 标签）被相机拒读的风险。
    SRC=$STAGE
    if [ -d "$PAYLOAD" ]; then
        SRC=$PAYLOAD
        for F in $FILES; do
            if ! cp -f "$STAGE/$F" "$PAYLOAD/$F" >>"$LOG" 2>&1; then
                echo "[pop] 中止：写 payload 失败 $PAYLOAD/$F（见 $LOG）"; return 1
            fi
            chown root:root "$PAYLOAD/$F" 2>>"$LOG"
            chmod 0644 "$PAYLOAD/$F" 2>>"$LOG"
        done
        echo "[pop] 模块 payload 已更新为修复版（本次 bind 的源）"
    else
        echo "[pop] 警告：$PAYLOAD 不存在，只做本次 bind（源 $STAGE）；重启后会回退"
    fi

    # ③ bind
    for F in $FILES; do
        T="$DST/$F"
        [ -f "$T" ] || { echo "[pop] 跳过 $F：目标 $T 不存在"; continue; }
        [ -f "$SRC/$F" ] || { echo "[pop] 跳过 $F：源缺失 $SRC/$F"; continue; }
        [ "$SRC" = "$STAGE" ] && chcon u:object_r:vendor_configs_file:s0 "$SRC/$F" 2>>"$LOG"
        if mount --bind "$SRC/$F" "$T" >>"$LOG" 2>&1; then
            echo "[pop] bind ok   $F  <- $SRC"
        else
            echo "[pop] bind FAIL $F（见 $LOG）"
        fi
    done

    do_verify
}

do_revert() {
    for F in $FILES; do
        n=0
        while grep -q " $DST/$F " /proc/self/mounts 2>/dev/null; do
            umount "$DST/$F" >>"$LOG" 2>&1 || break
            n=$((n + 1))
            [ "$n" -gt 5 ] && break
        done
    done
    echo "[pop] 已撤回原厂配置。payload 未改动 ⇒ 下次开机 post-fs-data.sh 仍会绑定模块版本。"
    echo "[pop] 若要连模块版本一起去掉，用 x8u-config-revert.sh。"
    do_check
}

case "${1:-check}" in
    check) do_check ;;
    apply)
        if do_apply; then
            echo "[pop] 强制停止相机，下次启动即读到新配置"
            am force-stop com.oplus.camera
            echo "[pop] 完成。现在打开相机 → 切到 POP，看预览是否**实时动**（不再定格）。"
            echo "[pop] 若仍黑/仍卡，跑 x8u-pop-diag.sh 取诊断包。"
        else
            echo "[pop] 应用失败，未改动相机。"
            exit 1
        fi
        ;;
    revert) do_revert ;;
    *) echo "用法: $0 {check|apply|revert}"; exit 2 ;;
esac
