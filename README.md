# OmniCam Muse

Find X9 Ultra（PMA110）的 KernelSU 模块与设置应用：理光 GR、POP、X10 滤镜与调色盘、大师色彩、自然模式、哈苏超清、移轴模式、柔光（弱 = HDF、中 = 1/4 黑柔、强 = 白柔）、XPAN 显影实况、XPan 取景净化。LSPosed 钩子加原生库在相机进程里工作，模块文件通过 KSU 绑定覆盖 `/odm` 配置。当前版本 **Muse 4.0（versionCode 300）**。

## 目录

| 路径 | 内容 |
|---|---|
| `app/` | 设置应用与钩子：Java（`hook/*`）、Kotlin（`ui/*`）、C++（`cpp/*`，大师色彩引擎 `master_*`） |
| `module/` | KSU 模块：脚本、`payload/` 配置与资源、`system/odm/lib64/` 库 |
| `tools/` | 生成与离线测试：`mastercolor/`（大师色彩）、`softlight/`（柔光权重表）、`symbols/`（混淆符号特征）、`patch_25mp.py`、`bake_master_luts.py`、`offline_tests/` |
| `tools/refs.py` | 仓库外参考资料的位置（`MUSE_REFS` 可改根目录） |
| `docs/` | `MODULE.md` 各功能的原理与涉及文件、`DEV_NOTES.md` 设备操作与排查 |

大师色彩里 HNCS 只是其中一个 look 家族（另有 Lumix、胶片、Canon、Darktable、Leica M9）；引擎代码以 `mastercolor` / `master_*` 命名。

大师色彩的“曝光对齐”把所选 look 对到原厂大师模式（LMTLut3）的曝光：中灰、中间调（中灰下 3 档到上 1 档的平均明度）、高光（上 1 档到传感器削顶点）、白点（90% 漫反射白）。对齐量是加在曝光补偿上的 EV 偏移，由 `tools/mastercolor/align_table.py` 生成 `MasterAlignTable.java`（中性灰阶，偏移与色温无关）；look 达不到原厂值时（胶片的高光与白点）不提供该项。

## 构建

环境：Windows、JDK 17、Android SDK 37、NDK 29.0.14206865、CMake 3.22.1、Python（含 numpy、pycryptodome）、Git for Windows。`local.properties` 写 `sdk.dir=…`（已忽略）。

```powershell
$env:OMNICAM_PYTHON = 'python'
.\build.ps1
```

`build.ps1` 先跑色彩目录检查、离线测试和脚本语法检查，再 `assembleRelease`。根目录有 `port.keystore`（已忽略）或设置 `OMNICAM_KEYSTORE` 时，用 `OMNICAM_STORE_PASSWORD`、`OMNICAM_KEY_ALIAS`、`OMNICAM_KEY_PASSWORD` 签名，产出签名 APK 并打包 `OmniCam-Muse-<版本>-KSU.zip`；否则只产出未签名 APK。证书与密码不入库，APK、zip 是构建产物，不入库。不同证书签发的 APK 不能直接覆盖安装。

## 发布与版本

每个正式更新一次提交加 `v<版本>` 标签。版本号在 `module/module.prop` 与 `app/build.gradle.kts` 两处。

## 许可证

本仓库以 [GPL-3.0](LICENSE) 发布。第三方来源与各自的许可见 [NOTICE.md](NOTICE.md)。

## 未验证

- 快启键指定模式：只用 adb 模拟快启键的 intent（`spruce_double_tap_launch_from_framework`）验证了冷启动、热启动和记忆模式还原，未用实体键逐个模式验证。
- 导出日志：未在真机上走完系统保存对话框并检查 zip 内容；无 Root 授权时“重启相机”打开应用信息页的流程未验证。
- 曝光对齐：原厂只用了 LMTLut3（照度与色温参数实际在六张表间选择与混合）；中性灰阶、主路径、无 lux blend；HNCS 的 intent / 色调曲线变体与 x7u 不在表内；未在真机上拍照核对对齐后的亮度。
- 设置界面五个底栏页（主页、拍摄、大师色彩、大师滤镜、杂项）在真机上的显示：深色模式、状态卡配色、新画的底栏图标。
- POP SDR 风格：成片是否与旧版 SDR 一致；前置 SDR 成片主图（NOP `0x24BE164` 后 BasicTone 的输入格式）和后置的滑杆回归。
- 前置普通拍照（非 POP）不受 POP 决策守卫影响；`unlock25mp` 变体里的同样守卫。
- Ultra HDR 关闭时的人像与前置调色盘；调色盘与滤镜同时启用时的先后；夜景、专业、大师模式回归。
- POP 在 1x、2x、10x 的全档位回归；`rear_ultra_tele` 全尺寸流在 POP 里是否被用到。
- 相册调色盘：保存后的成片、HDR 照片；相册滤镜列表里模块滤镜的出图。
- 相册编辑理光水印：在相册直接打开理光模式照片时的水印编辑、保存。
- 相册哈苏、LUMO、理光水印对所有照片开放（`WatermarkSeriesUnlock`）：相机照片三个系列已上机出现；截图等无 EXIF 机型的照片补本机厂商与市场名后的水印文字、理光水印在非理光照片上的参数行（全零 GRInfo）、保存未验证。
- 批量编辑水印（`BatchWatermark`）：已上机跑通 3 张添加哈苏水印（第一张前台选样式保存后回到桌面，其余在私有虚拟屏上各约 5 s 保存，实时通知显示进度与结果）。第一张选 ⊘ 的批量撤回也已上机（3 张恢复为无水印原图）。未验证：理光 GR 与个性画框样式的批量；几十张以上的长批量与中途锁屏；系统侧放行（`ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay`）只在本机 ROM 上验证。相册默认保存模式下每张都另存为新文件。
- 大师水印滤镜名：模块滤镜与导入滤镜在成片水印、套用自定义预设后的水印上显示自己的名称。
- 移轴：前置、快录；调节模糊带位置与强度的交互。
- 相册详情点地址打开地图：腾讯地图的跳转（测试机未安装）；只装一个地图应用时是否直接打开。
- 大师颗粒双尺：界面（双尺展开／收起、标题位置、重置）与各滤镜成片均未上机验证；800T 霓虹的默认大小 42 取自 MasterNeonGlow；预览是否跟随颗粒未验证；强度超过原厂值时的观感与 Meishe 颗粒量的有效上限未验证。
- `SeamlessBlurGuard` 的目标方法只在相机 7.006.103 的离线回放上唯一命中，未在真机上做 DexKit 解析。
- 柔光（`soft_light.cpp`）：只在大师模式、1x 主摄、室内台灯与日光灯下试拍；未试变焦、其他镜头、夜景点光源和逆光；预览纹理是 RGBA8 HLG，金字塔用 RGBA16F，只在本机 GPU 上跑过。成片 P010 按 BT.601 全范围解码（`EMsYuvColorMatrix` 0 / `EMsYuvRange` 1 的含义按枚举顺序推断）。HLG 与场景光之间用大师管线的中性灰轴曲线逐通道换算，LMT 的颜色部分与照度高于 280 时的色度混合未计入；HDF 当前模型（覆盖率 10%、散射约 20%、削顶光源按 2.6 倍真实亮度散射与直透、光晕按 28 mm 视场）只在大师模式 1x 室内台灯下试拍；2.6 只用一张台灯 RAWMax 标定（实测下限 1.8），未在晴天日光、夜景点光源与逆光下核对；画面边缘的光源经暗角后低于削顶点约 0.2–0.3 档，削顶判断未补暗角。5000 万像素黑柔约 4.5 s、HDF 约 2.5 s，在后台处理。白柔还没上机。
- XPAN 显影实况：只在边框水印 `hassel_style_xpan_1` 与烧写样式上看过相册播放和编辑器位置；其他边框样式的 `videoDisplayRect` 改写、不加水印时的实况、横拍未验证。
- XPan 取景净化：大小取景框按遮罩框短边占屏幕短边的比例区分（大约 0.93、小约 0.67，阈值 0.8），只在本机竖屏两种取景框上测过；关闭显影动画后的收起与缩略图刷新未在连拍下验证。
