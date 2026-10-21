# OmniCam Muse 模块

目标设备：PMA110 / Find X9 Ultra，相机 7.006.103。KernelSU 模块（ID `ricoh_pop_x9u`）配一个 LSPosed APK `omnicam-muse-<版本>.apk`，包名 `local.jiege.camera`。LSPosed 作用域：系统框架、相机、相册。

## 功能

- 理光 GR：GR 模式、影调、焦距条、快照对焦、相机与相册水印。
- POP：复古相机模式及资源、高像素；滑杆进成片，成片走 Ultra HDR。
- X10：清透、琥珀滤镜，调色盘及其成片调度（后置、人像、前置）。
- 大师模式：大师滤镜补全（徕卡 M9、富士 Rec.709 LUT，霓虹、和光），大师色彩（HNCS、Lumix、胶片、Canon、Darktable、Leica M9）。
- 自然模式、哈苏超清、普通模式 25MP 解禁、移轴模式、快门声。
- 设置在桌面或 LSPosed 中打开 APK。

各功能的原理与涉及文件见 `docs/MODULE.md`。

## 安装与回退

刷入模块并重启：刷入时模块安装包内 APK（`pm install -r -d`，结果写入模块目录的 `install.log`，签名不一致等失败不卸载已装应用），重启后相机、相册和系统框架都加载新版。同 ID 的模块原位更新。停用模块并重启即回退全部挂载。设置保存后相机下次读取即生效；快门声设置在铃声模式变化或重启相机后生效。

`post-fs-data.sh` 重建资源前，先延迟卸载本模块在 `/odm/etc/` 的配置与资源绑定，再从原厂目录合并资源，支持 KSU 软重启时重复执行。`/odm/lib64` 的库挂载由 Hybrid Mount 与 `post-mount.sh` 管理。
