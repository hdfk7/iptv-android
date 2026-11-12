# IPTV Android conventions

Use native Kotlin and Compose. Keep packages under cn.hdfk7.android.iptv:
- app: Application and Activity host
- core/model: plain models, no HTTP/JSON
- core/data/local, network, repository: persistence, parsing, refresh
- core/ui/theme: shared Compose theme
- feature/player: player state, ViewModel, screen and named components

Follow these Kotlin readability conventions: four spaces, multiline braced control-flow bodies, explicit return types, explicit lambda parameters, named callbacks and Compose slot arguments, no trailing lambda syntax, one top-level root per UI composable. Put text in strings.xml and graphics in drawable resources. Separate behavior from presentation.

Target Android 14 (API 34); do not call newer platform APIs without guards. Unit tests use JUnit 5 and names ending Tests. Do not embed channel lists; follow the explicit signing rules below. Build and run unit tests with ./gradlew :app:testDebugUnitTest :app:assembleRelease. For every device or emulator test, install the signed Release APK generated from the current source and verify the installed version before testing. Use the packaged Release APK for all UI checks and screenshots.

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

## TV 遥控器与焦点

- 保留现有面板的焦点实现。频道列表、节目单、更多、设置、代理、源管理和更新弹窗均以已验证的实际操作为准。没有可复现问题或明确改造要求时，不迁移焦点结构。
- 有焦点问题时，记录设备、按键序列、期望行为和实际行为。修复后验证移动、高亮、确认、返回恢复、列表滚动及输入法切换。
- 从任意频道分组按下键，有正在播放的频道时定位到该频道，否则定位到首个频道。浏览期间保持播放，确认后才换台。退出子弹窗时恢复原选择。
- TV 全屏首次按返回时保留退出确认提示。连续两次返回退出应用。

## 全量测试

用户要求「全量测试」时，执行以下回归流程。只用当前代码构建、配置好的正式签名和原包名 `cn.hdfk7.android.iptv` 覆盖安装，核对设备上的版本和 APK SHA-256。保留设备上的订阅、收藏和最近观看。先跑单元测试、Debug 和 Release Lint，再用正式包验证设备上的实际运行效果。只有收到发布指令时才使用仓库发布脚本发版。

- **设备与输入**：手机优先用已连接真机，分别测试竖屏、横屏，以及可用的窄屏和宽屏布局。TV 用实体设备或 TV 模拟器，全程用遥控器的方向、确认、返回和菜单键操作。使用 ADB 驱动 TV 时也只注入遥控器按键。
- **界面与截图**：逐页打开播放首页、底栏、频道列表和分组、更多菜单、今日节目单、设置、HTTP 代理、检查更新、源管理、添加和编辑源、删除确认及收藏相关界面。从正式包截实图，优先核对文字起点、基线、行距，以及图标大小、图标与文字的垂直对齐、左右边距和右侧按钮或开关的排列。对照手机和 TV 的同一表单，保证同组字段宽度、间距和对齐规则一致。检查长文字、空状态和列表滚动，节目名保持单行并正确省略。确认底栏、侧栏、弹窗和键盘之间没有意外遮挡。手机横屏空间不足时，确认滑动能到达所有字段和操作按钮。
- **TV 焦点**：逐个验证可操作项可达、首次焦点正确、移动和高亮一致、确认触发正确、返回恢复原选择。覆盖底栏、菜单、频道与分组之间切换，以及频道列表打开节目单再返回。验证正在播放标记与遥控器焦点各自正确。表单默认不弹键盘，确认输入框后才打开，取消键盘后仍可用方向键移动并再次进入编辑。
- **功能**：验证冷启动及启动频道设置、首帧、播放和暂停、连续换台、线路切换、铺满开关及重启记忆、收藏和取消收藏、最近观看、源管理、代理表单及更新检查。节目单检查今天的排期、当前和下一节目、跨日边界和高亮，当前节目最多一条，未来节目不能标记为正在播。涉及保存或删除时使用可恢复的测试数据。具备旧版正式包和同签名更高版本候选包时，验证从应用内检查、下载和覆盖升级后数据仍在。
- **结果**：按设备记录实际操作、结果和截图，明确区分通过、复现的问题和受条件限制的未验证项。修复后用新构建的正式包复测受影响场景和关联流程，交付时列出仍存在的问题。
