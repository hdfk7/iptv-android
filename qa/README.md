# Android 手机和 TV 回归

## 正式包回归

从项目根目录运行。准备 ADB、Python 3.10 以上及 Android SDK Build Tools。TV 高亮检查使用 Pillow，安装命令为 `python -m pip install Pillow`。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
python qa/release_ui.py --device phone --serial <手机序列号>
python qa/release_ui.py --device tv --serial <TV序列号>
python qa/tv_navigation.py --serial <TV序列号>
python qa/tv_exit.py --serial <TV序列号>
```

使用当前签名 Release 和原包名覆盖安装，核对 `versionName`、`versionCode` 和设备 APK 的 SHA-256。产物位于 `app/build/outputs/qa`。记录设备型号、Android 版本、APK 及通过、失败或待验证结果。手机横竖屏检查后恢复原旋转设置。TV 用遥控按键操作；检查实际高亮、确认动作和返回位置。实体电视与模拟器分别记录。

| 场景 | 验收 |
| --- | --- |
| 手机横竖屏与 TV 表单 | 检查添加、编辑、删除确认和 HTTP 代理的布局。手机横屏滚动后字段及按钮可达。TV 初次打开表单保持遥控焦点，确认输入框后弹出键盘，关闭键盘后方向键继续可用。 |
| TV 频道和节目单 | 全屏左键打开频道，频道行右键打开节目单，左键或返回恢复原频道焦点。方向键浏览期间保持原直播，确认后才换台。分组下键优先定位正在播放的频道，当前分组没有该频道时落到首项。 |
| TV 暂停与退出 | 暂停前画面持续变化，暂停后画面停止，恢复后重新出画。若只出现高亮采样失败，保留原步骤、截图和结果以供复测。返回先关闭浮层，全屏首次返回显示提示，2 秒内再按返回退出。 |
| 频道刷新与缓存 | 首条线路 404 后出画，手动切线能到达下一条。源返回 503 后重启，仍可读取该源的缓存频道与节目单。编辑 URL 后只使用新地址对应的缓存。 |
| 节目单 | 核对当前和下一节目、设备时区的当天排期及跨午夜记录。当前节目最多一条。快速切源时检查旧结果未覆盖新源。 |
| 代理和安全键盘 | 用可恢复的测试值保存代理配置，重启核对后恢复。OPPO 安全键盘用系统输入法状态或真机实际输入确认。受保护的截图不用于判断键盘是否打开，无法确认时记录待验证。 |
| 更新检查 | 检查当前版本状态和版本说明。只有更高版本清单和同签名 APK 可用时，才可覆盖应用内下载、系统授权和安装。 |

## 本地覆盖升级

在 TV 和手机分别运行，使用同包名、同签名且 `versionCode` 更低的旧版正式包。先准备可见订阅与最近观看。要验证非空收藏，在测试设备上先保留至少一条可识别的测试收藏。

```powershell
python qa/local_upgrade.py --device phone --serial <手机序列号> --old-apk <旧版APK> --build-tools <BuildTools目录>
python qa/local_upgrade.py --device tv --serial <TV序列号> --old-apk <旧版APK> --build-tools <BuildTools目录>
```

设备已经安装较新正式包时，可在允许测试降级的设备上显式加 `--allow-downgrade`。系统拒绝降级时保留现有安装和数据。脚本核对 APK 签名、版本和哈希，并对比升级前后的可见订阅、收藏数量和最近观看；核对非空收藏具体内容时还需打开收藏列表确认。结果在 `app/build/outputs/qa/local-upgrade`。本地安装与应用内更新分别记录。

传入 `--seed-url` 和 `--seed-guide` 可在旧版添加 `IPTV-QA-Upgrade` 测试订阅，并给当前频道添加一条测试收藏。TV 导航使用遥控按键，测试 URL 和名称通过 ADB 输入。升级测试完成后，删除该测试源；如果结果中的 `temporaryFavoriteAdded` 为 true，移除本次新增的收藏。

应用内升级使用配置了测试 HTTPS 清单的同签名旧包。清单指向同签名、更高 `versionCode` 的候选包。从设置进入检查更新，确认下载，按需授权并在系统安装器确认，安装后核对包哈希和原数据。公开渠道验证使用实际分发的旧包和清单。

给 `local_upgrade.py` 加 `--in-app` 走应用内升级。脚本通过触摸、输入框打字和遥控按键创建测试数据并驱动升级界面。ADB 安装仅用于准备旧版基线；新版通过 App 发起下载和系统安装。版本、哈希和系统窗口状态可只读核对。

## 受控播放与节目单

用自行生成的测试 MP4 启动 `qa/fixture_server.py`。服务仅监听电脑的 `127.0.0.1`；用 `adb -s <序列号> reverse tcp:18764 tcp:18764` 转发。使用独立测试源 `http://127.0.0.1:18764/release.m3u` 和 `http://127.0.0.1:18764/release-guide.xml`。将服务切换到 `/control?mode=release-offline` 模拟 503，结束后恢复 `/control?mode=ok`，移除测试源和转发。

`qa/debug_smoke.py` 是独立的调试诊断入口，使用 Debug 应用 ID 测试 404、跨重启缓存、节目单和出画后 HLS 分片停滞。它只在没有 IPTV Debug 包的设备上创建临时安装。正式回归使用上文的 Release 包。

```powershell
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -t 60 -c:v libx264 -pix_fmt yuv420p -movflags +faststart smoke.mp4
ffmpeg -i smoke.mp4 -t 5 -c copy -f mpegts smoke.ts
python qa/debug_smoke.py --serial <ADB序列号> --media smoke.mp4
```

## Release 换台耗时

构建并覆盖安装带 `-PAPP_DEBUG_SWITCH=true` 的签名 Release。运行 `python qa/release_tune.py --serial <ADB序列号> --rounds 40`。脚本核对已安装 APK 的 SHA-256，在当前源相邻频道间往返，记录成功样本的 P50、P95 及失败和超时。结果在 `app/build/outputs/qa/release-tune-*.json`。对比时使用同一设备、测试源和网络条件。
