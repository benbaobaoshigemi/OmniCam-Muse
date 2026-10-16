# 模块机制

Find X9 Ultra（PMA110，相机 7.006.103）上各项功能的原理与涉及文件。配置类改动在 `module/`，钩子在 `app/src/main/java/local/jiege/hook/`。设备排查方法见 [DEV_NOTES.md](DEV_NOTES.md)。

## 相册调色盘

相册（17.10.7）的“色彩风格”页（风格列表、调色盘面板、按照片 `filter.info` 还原相机的滤镜与调色盘）有三处：

1. 开关 `feature_is_support_color_palette` 要求 `ro.product.first_api_level >= 37`，本机是 36，其余条件（相机 `com.oplus.feature.color.palette.support`、IPU 调色盘、Find 系列、工程保存）都满足。钩子 `palette/GalleryColorPalette` 只把这一个开关答为真。
2. 调色盘模式经 `CombineLutProcessUnitSDK`、`PaletteColorTable` 出图，本机系统的 `com.oplus.camera.unit.sdk.jar` 没有这两个类，所有风格都渲染失败。`payload/ipu/` 是 X10（PMW110 A.50）的同名 jar，`service.sh` 放到相册 `files/jiege_ipu`（root 只读，SELinux 类别取目录的），钩子用 `ClassLoaderFactory` 以共享命名空间（库路径 `/product/lib64:/system/lib64`）建加载器，替换相册的该共享库加载器。两机的 JNI 库（`libCombineLutJni` 等）相同。jar 不可读时开关也不打开。
3. 出图路径：相册给 SDK 的是 GL 纹理；X10 SDK 的纹理直送分支（APS 纹理入口，`processImages`）本机没有，钩子让 `isTextureRequest` 为假，SDK 改走 GL 流水线，在 GPU 上转成 P010 后交给 `ipuColorPaletteProcess`（本机相册输入即 P010，`HBV16 for gallery P010`），再由 `libBasicTonePhoto.so` 路由到 X10 后端（相册传 CamMode 6）。调色表来自 `payload/x10_basictone/palette`。

相册的滤镜列表来自同一个 SDK 的 `FilterGroupManager`：`GalleryFilters` 在它初始化后把目录里默认开启的模块滤镜追加到普通组和大师组末尾（名称用目录标题，原厂序号不变）；导入滤镜在相机私有目录、相册读不到，不列出；LUT 不在 `/odm/etc/camera/meishe_lut`、`filters_lut` 的也不列出。

## 相册编辑理光水印

理光模式的照片尾部有 `gr.effect.info`（GRInfo，13 个 float），水印样式为 `gr_style_N`。相册编辑器只在照片解析出 GRInfo 且 `feature_is_camera_gr_supported` 为真时提供理光水印，而扩展信息解析（符号 `Extend.parse`）只在水印品牌含 realme（符号 `Brand.isRealme`）时读 GRInfo；本机照片的品牌是 OPPO。`GrGalleryWatermark` 在解析带 `gr.effect.info` 的照片期间让品牌判断返回真，其余照片不受影响。

理光水印使用独立的开关 `pref_watermark_gr_capture_switch_key`。`GrCameraWatermark` 在拍摄水印判断期间将照片水印的读取指向理光开关；设置页的水印摘要在理光模式下也读取该开关。`GrModeHooks` 的模式进入调用 `BaseMode.onStart()`，保留理光水印状态；相机传给相册的空样式代表关闭，编辑页保留该空值。

## 大师水印的滤镜名

新版大师水印（`com.oplus.support.new.master.watermark`）的滤镜名来自预设记录的 `filterName`：拍照时取当前滤镜序号的名称（符号 `FilterNames.nameAt`，`getString(名称资源)`），套用预设时由 `PresetDataUtil.applyPresetFilter` 写入。模块新增的大师滤镜借用大师童话的名称资源注册，因此水印都印“大师童话”。`MasterFilmLuts` 在这两处按同序号的滤镜类型（`FilterNames.types`）换成目录里的标题。相册重建水印时按序号在原厂列表里取名（`MasterModeInfo.getRealFilterName`），`GrGalleryWatermark` 改为优先用照片 `master.mode.preset.info` 里存的名称。理光风格的机型文字直接取 `MarketNameInfo`（符号 `MarketName.*`），`ModelSpoof` 也替换它。

## POP 成片的滑杆与 10X

POP 的光感、自然饱和度、色温、色调随拍照参数以 `KEY_MASTER_MODE_*` 送到引擎，成片要生效就必须经过 BasicTone。拍照链路里有四道门：

1. 初始化名单：SDK 把 `retro_camera_mode` 映射为 `common` 拍照配置，`oplus_camera_algo_switch_config` 里 `common` 的前后置项 `aps_algo_basic_tone` 都必须为 1，引擎才注册 BasicTone。
2. 决策规则：`SINGLE_ALGO_BASIC_TONE` 的条件要有 POP 分支（`REAR_NORMAL` 且 `retro_camera_mode`），另加前置 POP 分支（`FRONT_NORMAL` 且 `retro_camera_mode`，SDR 下前置靠它进入算法清单，其余模式要求 `ultra_hdr_enable`），并对其余使用 `common` 的后置三个模式、`AI_HIGH_PIXEL` 和前置 `FRONT_NORMAL/BOKEH/NIGHT` 加“非 POP”守卫，使它们保持原行为。文件：`payload/oplus_camera_preview_decision_config.json`（`post-fs-data.sh` 绑定到 `/odm`）与 `payload/unlock25mp/…` 同名文件（25MP 开关打开时读取）。
3. 流水线节点：`ApsCapDecisionRearNormal25021::addCommonPipelines` 与 `ApsCapDecisionFrontNormal25021::addCommonPipelines` 只在 `ultra_hdr_enable` 为真时加入 BasicTone 节点（算法 0x4e，三个节点）。
4. 全 0 跳过：BasicTone 预检在 POP 且四个值全 0 时直接返回；HybridRaw 交给它的是 10bit P010，跳过会把 P010 直接送去编码，主图损坏。

`libAlgoInterface.so` 把第 3 处的两条分支（后置 `0x24B0DA0`、前置 `0x24BE164`）和第 4 处（`0x2040688`）改为 NOP，POP 走 SDR 时后置与前置的滑杆也生效。

10X 条纹：长焦（物理相机 4）硬件 IPE 离线处理在“10bit 输入 → 8bit 输出”时输出损坏，与 ISP 调校文件无关。让 IPE 保持 10bit 输出、由 BasicTone 转 8bit 即绕开。

## 调色盘：人像与前置成片

调色盘算法 `aps_algo_color_palette` 在后置人像、前置人像、前置拍照的算法清单里，但流水线没有节点去执行它。`libAlgoInterface.so` 在三个类（`RearBokeh25021`、`FrontBokeh25021`、`FrontNormal25021`）的 `addCommonPipelines` 入口跳转到库末尾（`0x2902860` 起，约 1.6 KB，代码段 `p_filesz/p_memsz` 扩到 `0x2903000`）的共用例程：

- 调色盘算法未启用：直接返回；
- Ultra HDR 开：把 `paletteInit/Proc/Uninit` 追加进流水线数组；
- Ultra HDR 关且没有滤镜算法：走库里原有的单节点 `0x458` 路径。

`paletteProc` 的依赖：后置人像 `bokeh3`；前置人像 `copyUhdrP010`；前置拍照 `fbHdrConvertUninit`（不能依赖 `copyUhdrP010`，会成环使 `endCapture` 中止）；`aps_algo_rsp` 启用时再加 `rspProcess`，并让 `rspUninit` 依赖 `paletteProc`，否则有人脸时 RSP 提前释放蒙版，相机在 `~ReSamPlingStrategy` abort。

## POP 0.6x 拍照

POP 在 0.6x 拍照会让 HAL（`vendor.qti.camera.provider-service`）在 `NodeBase::SetupRequestData` 的请求环缓冲里 abort。0.6x 的决策选 `ALGO_HR_REAR_UW0P6X-1X_50M`，需要 `rear_wide` 与 `rear_ultra_tele` 的 `surface_key_capture_full_size` 输出流，而 `camera_unit_config` 里 POP 的用例 `retro_camera_case` 的 `capture_raw10_fullsize` 只列了 `rear_main`、`rear_tele`。现已与 `sat_photo_full_size_case` 对齐（main、wide、tele、ultra_tele）。

## 移轴模式

相机里完整的移轴模式出厂关闭，引擎算法 `aps_algo_tilt_shift`、GL 预览库和 `libCaptureBokeh.so` 的 `TiltShiftBokeh` 都在，缺的是配置，四层各需一处：

1. 特性标志 `com.oplus.feature.tilt.shift.photo.support`：HAL 侧写在 `payload/oplus_camera_config`（任何进程启动即可见），应用侧由 `service.sh` 写进相机 `override_config_data.xml`。
2. 模式列表数据库 `mode_data.db`：按特性标志建库，`service.sh` 开机检查库里有没有 `tiltShift` 行，没有就删库一次（`$MODDIR/tilt_db_reset` 标记保证一次）。不能靠抬高 `com.oplus.camera.mode.data.db.version`：HAL 里它是 Byte（最大 127），且重启后系统会在覆盖配置加载前先起相机进程，对 203 的库做“降级”重建。所以版本保持 102。
3. 镜头支持：`camera_unit_config` 把 `tilt_shift_mode`（操作模式 8001）加进五个后置镜头的 `mode_type_list`，并按 `photo_mode` 复制 `capture_stream_number`。
4. 界面特性：`camera_unit_feature_config.protobuf` 追加特性 `com.oplus.camera.feature.tilt.shift`，新增 `tilt_shift_mode` 条目（复制 `photo_mode`，`TiltShiftPhotoMode` 继承 `PhotoMode`），并在 `common` 与各后置镜头组追加该特性；前置不加。

成片模糊：拍照时引擎用预览决策算出的算法清单（`mApsAlgoFlags`），应用自己拼的清单只在没有决策结果时才用，而决策配置没有移轴节点，`aps_algo_tilt_shift` 永远进不了拍照清单（往决策 JSON 加节点无效，解析器跳过不认识的名字）。`hook/tilt/TiltShiftCapture` 钩 `ApsProcessor.createMetaItemInfo`，在 `mbTiltShiftOpen && mTiltShiftBlurValue > 0` 时把它插到 `aps_algo_rotate_mirror` 之前。

移轴保持 SDR：`tiltShiftProcess` 只接受 `NV21` 和 `YUV_420_888`，Ultra HDR 路径给它的是 10-bit 缓冲会被跳过，所以 `tiltShift` 不在 `capture.hdr.cap.mode.value` 名单里，成片比普通拍照亮一些，与原厂一致。要兼得需把移轴节点排到 `hdr_transform` 之后，那是引擎补丁，未做。

## POP 走 Ultra HDR 与前置 BasicTone

- 应用只对 `com.oplus.camera.capture.hdr.cap.mode.value` 名单里的模式发 `ULTRA_HDR_ENABLE=on`（`HdrAllRoutePresenter.B2` 优先读它）。`service.sh` 把 `retroCamera` 加进成片名单；预览名单 `preview.hdr.cap.mode.value` 不动。POP 成片因此带增益图（JPEG 里有 `hdrgm` 与 MPF 标记）。
- 前置 POP 滑杆：`common` 前置项（id 1）的 `aps_algo_basic_tone` 为 1；Ultra HDR 下前置流水线本来就加 BasicTone 节点，SDR 下靠上面第 3 处的前置 NOP。

## POP 的 SDR 风格

设置「POP 使用 SDR 风格」（`pop_sdr_style`，默认关）。成片是 SDR 还是 Ultra HDR 只取决于 `retroCamera` 在不在 `capture.hdr.cap.mode.value` 名单里；`hook/pop/PopSdrStyle` 钩 `CameraConfig.list`（`CameraConfig.u(String)`），开关打开时对该键返回去掉 `retroCamera` 的新列表（原列表是定长的）。名单在每次建会话时读取，切模式或切镜头后生效。模块侧的名单不变，SDR 路径需要的引擎补丁（上面的 NOP、决策里的 POP 分支）都已在。

## 前置 POP 拍完点缩略图闪退

Ultra HDR 拍摄后走“无缝 UI”路径：`PreviewRenderer.setSeamlessGaussianBlurBitmap` 在预览缩放状态下调 `BitmapUtil.cropCenterBitmapByScale`；缩放值小于 1（前置广角）时裁剪框大于位图，方法返回 null，调用方不检查就包成纹理，下一帧 GL 线程空指针，相机关闭。另外三处调用都判断了“缩放值 ≥ 1”。`hook/seamless/SeamlessBlurGuard` 在返回 null 时改返回原位图；目标方法由 `symbols.json` 的 `BitmapUtil.cropCenterByScale` 按错误日志字符串 `cropCenterBitmapByScale, error, sw: ` 定位。这是竞态，不是每次都崩。

## 注入状态与日志导出

相机看不到设置应用（包可见性），所以由应用来问：`call(com.oplus.camera.entry, "jiege.diagnostics")` 落到相机导出的 `EntryProvider.call`，钩子（`hook/common/Diagnostics.java`）只对模块 uid 返回状态：符号解析数、缺失数、错误行数、进程启动时间、加载钩子的模块 APK、最近 600 行模块日志，以及相机的大师滤镜原厂列表（应用存进 `MasterFilterStore`）。调用会在相机未运行时拉起相机进程。`Log.e` 计入错误数。

主页状态（`ui/Diagnostics.kt`）：LSPosed（xposedsharedprefs 可用）、KSU 模块（`/odm/lib64/libAlgoInterface.so` 与 `/odm/lib64` 不在同一设备，即绑定挂载生效）、相机注入、符号匹配、功能加载。全部正常时状态卡为绿色；相机仍跑着旧模块 APK 时为橙色，提示重启相机；其余异常为红色。“重启相机”“重启相册”用 `su -c am force-stop` 后重新打开，没有 Root 授权时打开应用信息页。主页的“日志”是二级页：“开启日志”用 Root 起一个 logcat 写入应用缓存（两个 8 MiB 轮转文件），关闭时停止并保留；“导出日志”写一个 zip：概要、相机状态与日志、模块设置（授权码只标记已保存）、大师滤镜配置、应用自身 logcat，有 Root 时加 LSPosed 模块日志、logcat 与记录下的日志。

## 理光 GR 快门声

GR 模式向相机的播放器请求自己的音效（14 快门，15/16 长曝光开始/结束，raw `camera_gr_shutter*`），PMA110 的相机里这三个文件是 0 字节，加载永远不完成，请求一直挂在“加载完再播”的队列里。`hook/gr/GrShutterSound.java` 在 `SoundPool.load(Context, resId, …)` 遇到这三个空资源时，改为加载模块里 realme 相机（RMX5200 16.0.10.501，`com.oplus.camera` 6.070.215）的原版音效 `assets/gr_sounds/*.ogg`（首次复制到相机缓存）。相机的快门声设置、情景模式和本模块的快门声功能照常生效。

## 快启键指定模式

快启键以 `android.media.action.STILL_IMAGE_CAMERA`、`android_camera_launch_type=spruce_double_tap_launch_from_framework`（并带 `android_spruce_key_launch_type`）经 `CameraImageActivity` 拉起 `Camera`。`CameraEntry` 会打开 intent 整数 `mode` 经 `CameraInfoCursor` 映射出的模式，但 PMA110 的映射表里没有大师（`professional`）和 GR。`hook/launch/KeyLaunchMode.java` 给快启键的 intent 放一个表里没有的 `mode`（9000），映射时换成设置里选的模式名（`settings/LaunchModes.java`，模式名取自相机 `mode_data.db`）。

相机把当前模式记在 `pref_camera_mode_key`，下次启动打开它。快启键启动时先记下原记忆模式，这次会话停止（`Camera.onStop`）时若仍停在快启键的模式，就写回原记忆；会话中手动换过的模式照常保留。`pref_camera_mode_key` 的 DataKey 在第一次用到时才解析，钩子安装时解析会让相机卡死。

## 柔光：HDF、黑柔与白柔

三档柔光都由 `libmsnativefilter.so` 的 ProMist 在 HLG 帧上合成：预览走 `MsRenderHDRTexture`（RGBA8 HLG 纹理），成片走 `MsRenderHardwareBuffer`（P010 硬件缓冲，先 1280×960 缩略图，后台再处理全尺寸）。`SMsEffectParam` +0x48 是柔光开关、+0x4c 是类型：弱 = 1、中 = 0、强 = 2。

替换在“拍摄 → 实验性功能 → 物理柔光”二级页里按挡开关（`soft_light_weak_hdf`、`soft_light_medium_black_mist`、`soft_light_strong_white_mist`，默认关），相机打开（变焦条创建）时以位掩码读入原生层，未开的挡走原厂。弱档为 HDF、中档为 1/4 黑柔、强档为白柔：`soft_light.cpp` 在调用期间关掉原厂 ProMist，让美摄渲染滤镜的其余部分，再对输出做光学散射：按大师管线的中性色调曲线（内置 gamma 表加六张原厂 LMT 灰轴的平均，逐通道；`soft_light_curve.h`，由 `tools/softlight/gen_curve.py` 生成）把 HLG 信号反解回场景光（18% 灰 = 0.18，传感器削顶约灰上 5.9 档），13 抽头降采样金字塔与逐级帐篷上采样链，每级每通道的权重来自物理光学模拟的蒸馏（`soft_light_weights.h`，由 `tools/softlight/gen_weights.py` 从工作区 `实验\柔光替换\pyramid_weights.json` 生成），HDF 散射约 20%（皮升墨滴，覆盖率 10%，集中在 0.6° 以内；HDF 属于 GR 的 28 mm 镜头，光晕按 28 mm 视场计算、不随变焦变化，黑柔与白柔随当前焦距）、黑柔约 15%（多在 0.3–1.3° 的光晕里，另吸收 18%，按透过率补回）、白柔约 26%（七成在 3° 以外，成乳白雾），Hybrid Raw 里已削顶的光源比记录值亮（RAWMax 台灯按削顶最少的通道至少 1.8 倍），HDF 按 2.6 倍真实亮度处理：接近削顶的像素送入金字塔前按峰值通道放大（削顶下 0.5 档起线性升到 2.6），周围约 0.1° 的平均亮度在削顶下 0.3 档以内时直透光也放大到 2.6 倍（按邻域均值判断，纹理不被拉开），压肩把光源压成一片白；光晕由光源的真实亮度撑起，散射比例因此只需约 20%，画面其余部分不发雾。黑柔与白柔的削顶像素按记录值散射：大师帧里被照亮的白色表面也会削顶，经宽光晕放大会像光源一样发光。超出传感器削顶的光按峰值通道软压肩，再按同一条曲线编码回 HLG。预览在美摄的 GL 上下文里跑，保存并恢复 GL 状态；成片在 CPU 上算同一套金字塔，把每个像素编码值的变化量加回 P010（色度按 2×2 平均），未散射处保持原值。

光晕是镜头前的角度，在画面里随焦距缩放：Java 侧（`softlight/SoftLight.java`）在变焦条创建时报 1x、`Zoom.setZoom` 时报当前变焦对应的等效焦距（`common/ZoomFocal.java`，按相机配置的变焦档位插值），原生侧按 log₂(焦距像素数) 在权重表里插值；超出表尾时金字塔基准每倍焦距上移一级。

## 大师调节栏的颗粒

调节栏在暗角前多一项“颗粒”，条目显示当前强度。点按弹出“强度”“大小”两根并排收起的半尺，照专业模式白平衡的色温／色调双尺（`WbBar`、`ToneBar`，监听 `ScaleBarListener` 用 `Proxy` 实现）：按下哪根展开哪根，另一根与其标题淡出，收起时复原；再点“颗粒”、点其他项或关闭调节栏时隐藏。刻度为参数 ×100，0–100。`hook/masterfilter/MasterGrain` 在菜单构造容器视图前把条目插进条目表；尺与标题加在原厂调节尺的父容器里，复制其布局参数。

默认值照原厂：Portra 400、800T 与三款富士大师 36／70（800T 开霓虹时 36／42），黑白 HC 40／47，正片 25／47，其他滤镜 0／70。值不进预设，按应用侧滤镜类型（`FilterNames.types` 在 `CaptureParams.filterIndex` 处）存全局键 `key_muse_grain_amount_<类型>`、`key_muse_grain_size_<类型>`，-1 为未设置。条目自身的值只表示当前滤镜是否设置过（`EffectMenu.modified` 之前同步），原厂按预设存值的回调对本项拦截；调节栏重置时清掉当前滤镜的记录。

成片：大师模式最后一个颗粒设置函数（`setPositiveFilterGrain`）之后，当前滤镜设置过才替换原厂颗粒量与大小；原厂无颗粒的滤镜强度大于 0 时填大师彩色胶片那组参数，颗粒量以负数下发。

`OCamFilterMeisheV2::setMesheFilterParam` 只给按名字认识的滤镜设颗粒：`800t.bin`、`fuji-nc.bin`、`fuji_cc.bin`、`fuji-proNegHi.bin`、`ccd_base_cold/warm.bin`，前缀 `portra400`，`800t_master.bin` 与三个富士大师文件，前缀 `blackandwhite_HC_` 或 `positive`。各分支在 `0x1ad02a4` 汇合；`tools/patch_grain_gate.py` 把这里改成跳到代码段尾 `0x2902ea0` 的跳板：颗粒量为负时取反，补上 `x22 = sp + 0x130`，进入黑白／正片的颗粒块 `0x1acfab4`（从 metadata 读全部颗粒参数）。原厂颗粒量都为正，不受影响。

标准不下发滤镜参数，拍照清单也没有滤镜节点（预览决策里被去掉）。强度大于 0 时，`ApsProcessor.createMetaItemInfo` 之后给大师模式的拍照 metadata 补上空的 `filter_type` 和同一组颗粒参数，并把 `aps_algo_filter` 插到应用自建清单里它之后的节点（眼部增强、超级文本、星芒、鱼眼、旋转镜像、移轴、水印）之前。`doFilterProcessV2` 在类型为空且黑柔、星光、复古都关时跳过节点（`0x1ad2e64`）；第二个跳板（`0x2902ec4`）照该函数读 `retro_filter_intensity` 的写法读 `filter_grain_amount`，为负才进入节点，节点不加载 LUT。

## 大师 LUT 槽与对比度

原厂大师 BasicTone 每帧把风格 LMT、SC（影调 Cont、饱和度 Sat）、CWCM（冷暖 CoWa、青品 CyMa）合成一张表：`CWCM(SC(LMT(x)))`，成片之后再接 ODT（开启时）与暗角，预览无 ODT。模块加两个槽，合成变为 `Post(CWCM(SC(Pre(LMT(x)))))`：Pre 放塑形类（对比度，以后的高光／暗部亮度），Post 放着色类（以后的分离色调、彩色 Fx 蓝、片基），恒等时不改任何调用。各槽是有序的阶段列表，阶段直接作用在原厂表的格点值上。

- native：`app/src/main/cpp/master_lut_slots.cpp`。成片挂 `libBasicTonePhotoX9.so` 的 `BasicTone::MergeLutTet_resize` 与 `BasicTone_OGL::load3DLut<half>`，按源表是否为 LMT（`this+0xd48`）、查找表是 SC∘CWCM（`+0xd58`）还是 ODT（`+0xd50`）分情况套 Pre／Post；只作用于大师帧（`basicToneMasterFrame()`，由 `gr_basictone.cpp` 的 processCore 钩子按帧标记）。预览挂 `libCombineLut.so` 的 `MergeLutTet_resize2rgb`（四线程分段）；原厂四项调节全为 0 时不调用合成，由 Java 侧对返回表套 `Post(Pre())`。
- Java：`hook/masterlut/MasterLutSlots.java` 传参数、按 `Lut3d.load` 的模式名标记大师预览、参数变化时同步调用 `Lut3d.update` 重算预览表（与原厂调节一致，拖动实时生效）。
- 对比度：`hook/masterlut/MasterContrast.java`。条目插在影调之后，用原厂影调图标；影调改用理光影调图标 `gr_mode_effect_tone_icon`。量程 −100..100（`EffectMenu.bipolar`），全局持久化、不进预设，重置归 0。效果为理光对比度：理光 SC 表 Sat=0 一列 Lut3／Lut4／Lut5，按 `fuseUtilLut` 的方式在节点间线性插值。
