# IPTV Android conventions

Use native Kotlin and Compose. Keep packages under cn.hdfk7.android.iptv:
- app: Application and Activity host
- core/model: plain models, no HTTP/JSON
- core/data/local, network, repository: persistence, parsing, refresh
- core/ui/theme: shared Compose theme
- feature/player: player state, ViewModel, screen and named components

Follow these Kotlin readability conventions: four spaces, multiline braced control-flow bodies, explicit return types, explicit lambda parameters, named callbacks and Compose slot arguments, no trailing lambda syntax, one top-level root per UI composable. Put text in strings.xml and graphics in drawable resources. Separate behavior from presentation.

Target Android 14 (API 34); do not call newer platform APIs without guards. Unit tests use JUnit 5 and names ending Tests. Do not embed channel lists; follow the explicit signing rules below. Build and run unit tests with ./gradlew :app:testDebugUnitTest :app:assembleRelease. Verify the installed signed Release APK before collecting device test results. For the full regression, start with the signed older version and upgrade both devices in the app.

## Source and playback behavior

`feature/source` owns source management state and dialogs. One source is active at a time. Aggregate program URLs by normalized program name, including across source contributions in the favorites source. Keep favorite contributions separately so refreshed URLs retain their origins. Next/previous always change the program. Playback probes candidate URLs in order and stops probing when one is ready. Timeouts are configured by STREAM_*_TIMEOUT_MS in gradle.properties.

## Git and packaging

For production releases, run the repository's `upload-release.bat` on Windows or `upload-release.sh` on macOS/Linux. Use the script's default clean build and upload flow unless the user explicitly requests another mode. After publishing, verify the public `latest.json` and confirm that the downloadable APK matches the signed local artifact.

Create or push a Git release tag only when the user explicitly requests that action. A request to commit, push a branch, or publish an APK does not authorize tagging.

Use concise Conventional Commit subjects with a Chinese summary (`feat:完善节目收藏`). Keep each commit focused and describe affected modules and verification in PRs. Ignore build output, IDE caches and local.properties. By the user's explicit instruction, the local APK signing file `signing/sign` and APK_SIGN_* settings in gradle.properties are included as project files and must not be added to ignore rules. Do not print signing passwords in logs or reports. Debug uses .debug application ID suffix; release uses the base ID. APK filenames use app name, build type, version and timestamp.

Refresh on foreground entry and manual action only. Do not schedule periodic refresh. Do not add compatibility paths solely to migrate data or jobs from earlier app versions. Proxy settings apply to playlist and Media3 networking; never send proxy credentials as origin Authorization headers or log secrets.

## 变更范围与行为

- 精简代码时，保留相同输入和状态下的结果、操作顺序、按键响应、焦点、滚动、返回行为及数据。只合并等价的重复实现，或移除经核实无调用且无行为影响的代码和资源。
- 修复逻辑时，先确认具体的不合理行为及触发条件，再做最小改动并复测。现有实现方式不同于示例或其他面板，本身不构成缺陷。
- 文案或界面呈现调整只涉及对应的可见内容，保留操作、状态流转和数据处理。文案使用功能名称、动作按钮、必要状态及错误原因。删除教程式提示、按键指示、重复说明和纯引导浮层。版本说明记录实际变更。
- 刷新成功后先原子写入同一源地址的有效非空列表，再更新快照和时间。失败时读取该地址的旧缓存。状态变化可复用未变化的频道列表；切源、改地址或节目单关联变化后按当前数据生成。缓存满 24 小时或时钟早于写入时间时标记过期，过期状态不触发定时刷新或删除。
- 节目单在切源或退出前台时响应取消，解析期间及写缓存前检查取消。代理保存和安装授权状态写入在后台完成，成功后才应用代理配置或打开系统授权页。

## TV 遥控器与焦点

- 保留现有面板的焦点实现。频道列表、节目单、更多、设置、代理、源管理和更新弹窗均以已验证的实际操作为准。没有可复现问题或明确改造要求时，不迁移焦点结构。
- 有焦点问题时，记录设备、按键序列、期望行为和实际行为。修复后验证移动、高亮、确认、返回恢复、列表滚动及输入法切换。
- 从任意频道分组按下键，有正在播放的频道时定位到该频道，否则定位到首个频道。浏览期间保持播放，确认后才换台。退出子弹窗时恢复原选择。
- TV 先用返回键关闭当前浮层。全屏首次按返回显示退出提示，2 秒内再次按返回退出，超过 2 秒提示自行消失。

## 全量测试

按 [QA 流程](qa/README.md) 执行。用 ADB 卸载两台的 IPTV，选一个支持应用内更新的正式旧版，用发布脚本打旧包，再用 ADB 安装。两端通过旧版 App 准备并核对订阅、收藏和最近观看。两端基线通过后才用发布脚本打新版，然后从旧版 App 检查更新、下载和安装，核对数据并做全量测试。每一步完成后先报告实际结果。
脚本通过触摸、输入框打字和遥控按键执行操作。包、窗口、日志和进程状态可只读核对。应用内升级通过正常入口，禁止直接安装新版、写入数据或跳转页面。旧版安装和应用内升级如遇人工确认，停在当前界面等待，确认结果后再继续。

记录测试结果后，经系统界面清除指定两台设备上 IPTV 的应用数据，只保留两台设备上已安装的新版 App。清理主机上本轮生成的新旧 APK、截图、视频、日志和其他临时测试文件，并停止测试服务、撤销本轮转发。
