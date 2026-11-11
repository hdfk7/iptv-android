# Android 手机和 TV 场景回归

## 发布前回归

在项目根目录执行以下命令，分别指定手机和 Android TV 的 ADB 序列号。脚本覆盖安装本次签名 Release，核对设备上 APK 的 SHA-256，并保存手机横竖屏及 TV 的源表单、代理表单截图到 `app/build/outputs/qa/release-ui`。手机旋转设置会在测试结束后恢复。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
python qa/release_ui.py --device phone --serial <手机序列号>
python qa/release_ui.py --device tv --serial <TV序列号>
```

两端再执行以下场景并记录设备、Android 版本、APK 和结果。

| 场景 | 验证步骤 |
| --- | --- |
| 待播与线路切换 | 用开头至少 0.5 秒全黑、后续有画面的测试 MP4 启动 `fixture_server.py`。在隔离的测试环境中把第一条订阅配置为 `/release.m3u`，关闭启动续播后冷启动。核对第一条线路 404 后请求后备线路、跳过黑帧并静音定格。按播放键确认开始播放。 |
| 节目表与跨重启缓存 | 为同一测试源填写 `/release-guide.xml` 的完整 HTTP 地址。节目表按 `tvg-id` 匹配，侧栏应显示当前和下一节目，打开「节目单」确认当天已播和后续排期。将测试服务切换到 `/control?mode=release-offline` 后冷启动，确认预览和排期仍可见。完成后恢复 `/control?mode=ok`。 |
| 布局和焦点 | 横竖屏分别打开添加源、编辑源和代理，检查默认画面上的字段与按钮，以及弹出键盘后字段可滚动到达。TV 用方向键打开节目单、浏览条目并返回频道列表，检查焦点恢复。 |
| 旧版应用内升级 | 在独立设备或可恢复的模拟器安装旧版签名包（覆盖最低需要验证的版本，如 1.2.19）。预先添加订阅、收藏并产生最近记录。更新清单指向版本号更高且签名一致的候选包后，从应用设置内发起下载和安装。安装后核对 APK、订阅、收藏和最近观看，记录升级前后的版本。 |

测试服务只监听电脑的 `127.0.0.1`。手机和 TV 均可通过 `adb -s <序列号> reverse tcp:18764 tcp:18764` 访问，设备填写 `http://127.0.0.1:18764/release.m3u` 和 `http://127.0.0.1:18764/release-guide.xml`，结束后移除该转发并恢复测试源。发布使用仓库的发布脚本，发包后核对公开清单和 APK 的 SHA-256。

私有预发验证可用旧版源码构建同签名的测试基线，通过 `-PAPP_UPDATE_MANIFEST_URL=<预发清单的 HTTPS 地址>` 指向预发环境。预发清单的版本号必须高于旧版。公开渠道升级验证使用已分发的原始旧版 APK。

此目录提供 ADB 场景驱动、受控播放源和截图图册生成器。下述旧版自动场景仅用于可恢复的 Android TV 模拟器。手机和真实电视按文末清单验证，保留应用数据。

测试分为真实仓库验证与受控场景。受控媒体为自行生成的测试信号，不使用第三方样片；本地服务只监听 127.0.0.1，模拟器通过 10.0.2.2 访问。故意构造 404、503、空列表、HTML、HLS 分片清单、读取延迟和迟到响应。

运行前必须停止应用并备份 files、databases、no_backup，备份路径为 `%TEMP%/iptv-qa-original-data.tar`。测试会新增/删除测试源、清理模拟器内调试应用数据、切换模拟器网络和显示尺寸。结束时执行 restore_emulator.py 恢复原数据和设置。

依次执行 android_tv_suite.py 的 real、forms、playback、volume、library_cases、source_edges、cache_cases、offline_cases、resolution_cases 阶段。每项会保存未经修改的 PNG、结果断言、测试数据和时间到 `app/build/outputs/qa`。首轮发现的问题保留修复前/后证据。

```powershell
python qa/fixture_server.py --media <本机生成的测试信号.mp4> --port 18764
python qa/android_tv_suite.py real
# 按上述阶段顺序继续运行
python qa/restore_emulator.py
python qa/build_gallery.py
```

完成后打开 `app/build/outputs/qa/index.html`。可按场景分组、搜索、点击放大、方向键翻页；`iptv-scenario-screenshots.zip` 包含图册和全部原始截图。scenarios.json 保存逐项数据，summary.json 汇总结果，restoration.json 记录恢复结果。

不能把模拟器验证等同于小米实体电视测试。图册会列明这些边界。

1.2.0 首页收藏、代理、源管理、播放按钮改为图标，无障碍说明保留在 content-desc。旧测试脚本中依赖这些按钮 text 的步骤需要改为 content-desc。U01–U03 为新版实际截图。代理 JVM 测试覆盖地址校验、HTTP 407 认证及关闭直连，模拟器完整代理播放和 HTTPS CONNECT 尚未完成实测。

旧版图标界面回归：

```powershell
python qa/interaction_audit.py focus
python qa/interaction_audit.py flows
```

需要 Android 14 TV 模拟器 emulator-5580 和 fixture_server.py。保留已有数据并新增 Audit 200 / Audit Main 测试源；不会清除或恢复应用数据。focus 阶段检查冷启动、切源、200节目长列表、第40项往返及全屏返回；flows 阶段检查全屏默认控件、暂停、方向切台、收藏、空收藏退出、设置取消、错误重试及后台暂停恢复。

## 1.3.0 发布回归

从当前源码构建签名 Release 包，在 Android 11（API 30）电视模拟器和 Android 手机安装，核对 `versionName=1.3.0` 和 `versionCode=25`。运行 `testDebugUnitTest`、`lintDebug` 和 `lintRelease`。

| 场景 | 验证步骤 |
| --- | --- |
| 手机布局 | 竖屏检查底栏四个等宽操作区和底部频道面板，横屏检查左侧频道面板。点击频道后面板收起并开始播放。 |
| 电视遥控器 | 确认键或 INFO 唤出底栏，暂停后恢复播放。用上下键和专用频道键换台，确认切换后的频道开始播放。 |
| 国内精选 | 手机点分组标签，电视用左右键切组、上下键浏览。列表焦点留在当前分组，确认频道后才切换直播。 |
| 设置与更新 | 从「更多 → 设置」打开 HTTP 代理及检查应用更新。电视验证上下键能到达两个入口，退出子页后焦点回到原入口。更新页核对本版说明。 |
| 源管理与线路 | 用独立测试源检查添加、启用、删除和恢复原源。用可控视频分别验证质量排序、HTTP 404 切线和直播卡顿后切线。 |

更新安装需要服务端提供版本号更高且签名相同的 APK。电视模拟器和小米实体电视应分别记录测试结果。

本次使用 Android 11 电视模拟器和 Android 13 OPPO 手机。两端均验证了直播、切台、收藏、设置、版本说明及测试线路排序和故障恢复。54 项单元测试通过，Debug 和 Release Lint 均为 0 错误。本次设备验证覆盖了从旧版签名包覆盖安装到 1.3.0，应用内下载新版和小米实体电视另行验证。


## Debug 播放和节目表回归

先执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。使用下面的命令生成测试信号，在电视模拟器和手机上分别指定 ADB 序列号运行。

```powershell
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=25 -t 60 -c:v libx264 -pix_fmt yuv420p -movflags +faststart smoke.mp4
ffmpeg -i smoke.mp4 -t 5 -c copy -f mpegts smoke.ts
python qa/debug_smoke.py --serial <ADB序列号> --media smoke.mp4
```

脚本按打包清单安装 Debug 包并核对版本。只在没有 IPTV Debug 包的设备上创建临时测试数据，运行结束清理临时安装与 ADB 转发。覆盖 404 自动切线、节目表重定向与当前/下一节目、订阅失败后的缓存续播、重启后优先使用稳定线路、冷启动恢复频道，以及首帧之后 HLS 分片停滞的恢复。日志中记录首帧、切台总耗时和检测到故障后的恢复耗时。


## Release 换台耗时

先覆盖安装当前正式签名包。构建属性 `APP_DEBUG_SWITCH=true` 提供仅包含耗时、线路是否首试和换台编号的日志。运行 `python qa/release_tune.py --serial <ADB序列号> --rounds 40`，脚本会核对已安装包的 SHA-256，冷启动后在当前源相邻两个频道之间往返。预热四次，按成功出画面的样本计算 P50（中位数）和 P95（最近秩），失败和超时另行计数。结果及原始数据保存在 `app/build/outputs/qa/release-tune-*.json`。真实源及网络状态会影响结果。
