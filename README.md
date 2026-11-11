# iptv

原生 Kotlin / Jetpack Compose 播放器，使用 Media3 ExoPlayer，支持 Android TV 和手机。最低安装要求为 Android 8.0（minSdk 26），目标平台为 Android 14（targetSdk 34），编译平台为 API 37。动态维护直播源，单源播放，按频道汇聚线路并跨源收藏。

## 构建与安装

Android Studio 打开本目录。使用支持 AGP 9.4.1 的 Android Studio、Gradle 9.8、JDK 25 和 SDK Platform 37。SDK 路径放在忽略的 local.properties。AGP 内置 Kotlin。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

APK 输出到 `app/build/outputs/apk/debug`、`app/build/outputs/apk/release`，文件名为 `IPTV-构建类型-v版本-时间戳.apk`。从 Release 输出目录的 `output-metadata.json` 获取本次构建的文件名和版本。

```powershell
$releaseDir = 'app/build/outputs/apk/release'
$release = (Get-Content "$releaseDir/output-metadata.json" -Raw | ConvertFrom-Json).elements[0]
adb install -r (Join-Path $releaseDir $release.outputFile)
adb shell dumpsys package cn.hdfk7.android.iptv | Select-String 'versionCode=|versionName='
adb shell am start -n cn.hdfk7.android.iptv/cn.hdfk7.android.iptv.app.MainActivity
```

Debug 包名 `cn.hdfk7.android.iptv.debug`、启动器名称 IPTV；Release 包名 `cn.hdfk7.android.iptv`、名称 IPTV。电视需开启开发者选项并完成 ADB 授权。macOS/Linux 使用 `./gradlew`。

最初的 APK 使用临时调试签名；如新签名覆盖旧包发生冲突，需先卸载旧包，卸载将清除其数据。新版带 .debug 后缀的包可与最初无后缀包并存。

## 签名、打包与 Git

实际 APK 签名文件在 `signing/sign`。gradle.properties 中 APK_SIGN_STORE_PATH、APK_SIGN_STORE_PASSWORD、APK_SIGN_KEY_PASSWORD_DEBUG_ALIAS、APK_SIGN_KEY_PASSWORD_DEBUG、APK_SIGN_KEY_PASSWORD_PROD_ALIAS、APK_SIGN_KEY_PASSWORD_PROD 配置签名。Debug / Release 分别使用 debug / prod signingConfig。按用户要求，签名文件及口令作为项目文件保留，不忽略；日志和文档不显示口令。

APP_DISPLAY_NAME 控制启动器名称和 APK 文件名；APP_DEBUG_SWITCH 生成 BuildConfig 开关。使用 Java 11 编译级别、JDK 25 daemon、Debug applicationIdSuffix、Release optimization.enable=false 与 proguard 配置。

Git 提交消息使用 `type:中文说明`，每次聚焦一个逻辑变更；PR 说明模块、行为和验证。忽略构建输出、IDE 缓存和 local.properties。项目已初始化 Git，远程分支为 origin/master。

## 源管理

顶部当前源或源管理按钮打开源面板，支持增加、修改名称/地址、删除和启用。始终只启用一个源，普通源不会混入其他源节目。新增不自动切源；删除当前源后使用剩余首个源，没有其他源时进入收藏源。切源停止原源播放，列表回到顶部。

支持 HTTP(S) 扩展 M3U 频道列表、GitHub blob 文件链接，以及三个内置仓库的主页地址。默认源为“国内精选”“综合频道”“全球频道”。重复地址、空名称和无效地址会显示错误。M3U8 可以是频道列表，也可以是用于播放的 HLS 清单，源管理只接受前者。

内置「国内精选」提供频道分组和央视频道名称。电视在频道侧栏用左右键切分组、上下键浏览，手机可点分组标签。央视分组按台号排序，收藏和切台沿用原有频道标识。订阅刷新时会参考上游 `response-time` 排列尚无本机播放记录的线路，实际播放质量仍优先于上游测速。

## 节目与轮询

按节目名称聚合记录，不按分组拆分节目。地址去重并保留出现顺序。名称统一全角/半角、大小写、空格，常见 CCTV1 / CCTV-1 / CCTV1综合 / CCTV1高清 合并。CCTV5 与 CCTV5+、CCTV4 与 CCTV4K、CCTV4欧洲与 CCTV4美洲保持区分；其他名称不猜测别名。

上一节目、下一节目和频道键切换节目。尚无质量记录时按源顺序尝试线路，有记录时按播放稳定性、分辨率、帧率和启动耗时排序。播放中更新排序会保留当前 URL。最近的线路评分和失败记录在本机保存 7 天，最多 512 条，按网络类型和代理地址分别使用。遇到错误、缓冲或画面停滞超时、直播意外结束时，尝试剩余线路。全部失败后可重试、手动选线或切台。

超时在 gradle.properties 中配置，单位毫秒，使用正整数，修改后重新打包：

```properties
STREAM_CONNECT_TIMEOUT_MS=5000
STREAM_READ_TIMEOUT_MS=5000
STREAM_PROBE_TIMEOUT_MS=8000
```

分别为连接、停滞读取、每条线路持续缓冲或画面停滞的最大等待时间。线路入口返回 401、403、404 或 410 时直接尝试下一条，分片和暂时性错误沿用 Media3 重试策略。慢源可能被跳过，可按网络条件调整。

## 跨源收藏

当前节目旁或全屏控件中的星标可收藏，保存每个“源 ID + 节目名称”的地址快照。不同源收藏同名节目后，收藏源只显示一个节目，合并这些已收藏来源的不同地址并标明来源，不加入未收藏源的地址。

普通源取消收藏移除当前源的贡献；收藏源取消一个节目移除该节目全部贡献。收藏源可独立启用，切台按聚合后的节目顺序操作。

刷新原源按稳定节目名称更新收藏地址；在收藏源刷新时，刷新仍然存在的贡献源。删除源或新列表找不到节目时保留最后快照，不静默丢失收藏；删除的源不再刷新。源、当前选择和收藏使用私有目录原子 JSON，重启保留，关闭备份和设备迁移。

## 全屏与遥控器

电视默认全屏播放。按确认键、右键或 INFO 打开半透明底栏，左键直接打开频道侧栏。底栏保留频道、播放／暂停、收藏和「更多」，6 秒无操作后隐藏。频道侧栏保持打开，确认后切台并收起浮层。上下键在频道侧栏中移动焦点，收起侧栏和菜单后可直接换台。专用频道键仍可换台。

「更多」集中源管理、刷新、线路切换、重连、音量和设置。返回键逐层关闭侧栏、菜单和底栏，电视全屏无浮层时连续按两次返回退出。媒体键可播放或暂停，系统音量键保留原生行为。

手机与电视共用全屏播放器、配色和半透明控件。手机点击画面唤出底栏，竖屏频道从底部展开，横屏从左侧展开。「更多」提供触控切台入口。打开的频道面板保持可见，选择频道后收起。

列表页底部左侧为上一节目、播放/暂停、下一节目、重试；右侧为线路状态、音量减/加。固定音量设备提示使用电视/功放遥控器。离开前台释放播放器，无后台媒体服务或通知权限。

## 自动更新和缓存

进入前台自动刷新，也可手动更新。没有每日定时任务。更新失败保留缓存和收藏。

按地址哈希独立缓存，切源先显示相应缓存。旧源延迟响应不覆盖当前源，修改 URL 不混用旧地址缓存。有效非空列表原子写入成功后才更新时间，网络、格式、空列表和写盘失败保留旧缓存。

列表下载的连接、读取超时各 15 秒，单次请求总时限 45 秒，解压后的列表上限 4 MiB。切源或应用新代理配置会取消旧的订阅请求。列表入口不跟随重定向。订阅和播放使用 HTTP(S)，拒绝 URL 内嵌用户名密码和无效端口。公共流含 HTTP，因此允许明文流量。应用不执行 M3U 中的认证、Cookie、Referer 或解密指令。

## EPG 与台标

M3U 文件头的 `x-tvg-url` 或 `url-tvg` 指向 XMLTV 节目表，优先通过 `tvg-id` 匹配频道，找不到 ID 时使用唯一的标准化频道名。源管理可以填写可选的 XMLTV 地址，以指定该源使用的节目表。频道侧栏显示正在播和接下来的节目，「节目单」显示当前频道在设备时区内的当天排期。内置「国内精选」在上游未声明节目表时使用与「综合频道」相同的 XMLTV 节目表。跨源收藏保留各贡献的节目表地址和频道 ID。兼容 gzip 节目表和 HTTP(S) 重定向，每个列表最多读取四个节目表，每个解压后上限 24 MiB。节目表在进入前台、切换有效节目表或手动刷新时获取，启动时可先使用 24 小时内的本地缓存。台标元数据暂未用于界面。

## 源仓库检查（2026-11-05）

| 仓库 | 默认列表 | 检查结果 |
| --- | --- | --- |
| [best-fan/iptv-sources](https://github.com/best-fan/iptv-sources) | [cn_all.m3u8](https://raw.githubusercontent.com/best-fan/iptv-sources/main/cn_all.m3u8) | 根目录 8 个 M3U8，近期每日自动提交；README 声明 MIT，未发现独立 LICENSE |
| [suxuang/myIPTV](https://github.com/suxuang/myIPTV) | [ipv4.m3u](https://raw.githubusercontent.com/suxuang/myIPTV/main/ipv4.m3u) | 包含 IPv4、IPv6、移动/APTV 列表和图片/播放器资源；人工不定期更新，未发现独立 LICENSE |

均通过 Git clone 检查，默认分支 main。myIPTV 文件头含 x-tvg-url，已适配；部分 IPv6/运营商专用线路依赖相应网络，默认 IPv4。未来更新频率不保证。

应用仅打包入口，不嵌入播放列表或流媒体。观看权利和可用性取决于源提供方，仓库许可不代表节目授权。请遵守来源条款与适用法律，仅在获得必要授权时使用。不绕过访问控制，不保证所有节目可播。

## 架构与验证

- app：宿主、生命周期和遥控器事件。
- core/model：源、节目、收藏、名称归一化及线路聚合。
- core/data/local、network、repository：原子持久化、限长下载/解析、业务规则和刷新隔离。
- core/ui/theme、components：主题和统一焦点组件。
- feature/player：节目、全屏播放与收藏；feature/source：源列表、表单和删除确认。

Kotlin 使用显式参数和命名回调，无尾随 Lambda；界面单一根组件，文本在 strings.xml，行为与布局分离。

JUnit 5 覆盖解析、线路顺序、源管理、跨源收藏、离线缓存、刷新竞争、代理认证、取消下载、线路排序与更新包下载限制。Android 端使用当前源码构建的签名 Release 包验证，先核对安装版本。QA 场景、测试条件和产物见 `qa/README.md`。

## HTTP 代理

从首页设置打开“HTTP 代理”，填写 IPv4/IPv6、端口及可选用户名/密码，开启并保存。支持 HTTP 转发、HTTPS CONNECT 和 Basic 代理认证。订阅下载、视频清单及分片使用同一配置；保存后重连当前节目并刷新当前源。关闭后直接连接。代理故障保留订阅缓存。配置使用 Android Keystore AES-GCM 加密保存。配置不可读时暂停订阅和播放请求，重新保存代理配置后恢复。

「更多 → 设置 → 启动时继续观看」默认关闭。开启后，冷启动恢复上次选择的源和频道，优先使用缓存。源已删除时回到默认源；源仍在、频道已消失时保留频道列表，等待选择。关闭时冷启动加载第一个订阅源，等待手动播放或选台。没有普通订阅源时显示收藏源。 频道侧栏的「最近」按源保留最多 20 个已开始播放的频道，最近播放的排在最前，频道已从列表移除时不再显示。

待播页按源和频道读取 24 小时内保存的预览画面，取得新的可用画面后替换。应用最多保留 12 张。没有可用的缓存和直播画面时显示待播背景。

打开电视频道侧栏时，遥控器焦点定位到正在播放的频道，有焦点的行显示亮色描边，当前频道显示圆点。

电视上下键切台时首尾循环，一次按键只切换一次。侧栏打开时，上下键只移动焦点，确定键才切换频道。

界面使用统一深灰背景、灰色面板与白色焦点，焦点按钮使用深色前景保证对比；代理入口采用齿轮图标。配色集中在 core/ui/theme/IptvTheme.kt。


操作图标使用统一圆形按钮，节目卡片使用 16dp 圆角；焦点以白色填充、深色图标与短时缩放表示。缩放仅作用于绘制，不改变列表布局和焦点位置。

## 交互回归

1.3.0 的版本号为 `versionCode 25`。电视回归使用 Android 11（API 30）模拟器，手机回归包含竖屏和横屏。设备检查项见 [QA 清单](qa/README.md#130-发布回归)。

电视端使用当前签名 Release 包验证。启动后等待底栏收起，按确定唤出底栏，再按确定打开频道侧栏。上下键移动焦点时确认直播仍在原频道，按确定后应切台并收起浮层。打开「更多」并进入源管理或设置，返回时检查焦点回到「更多」。`qa/interaction_audit.py` 的入口和控件定位对应旧版电视布局。

设置菜单需要用遥控器验证完整路径。进入时焦点落在「HTTP 代理」，下键到「检查应用更新」，上键回到代理。分别按确定进入两个子页，再按返回，焦点应回到刚才的入口。小米电视使用实体遥控器核验这一流程。

列表焦点按源和节目 ID 记录，不依赖行号；刷新重排仍能定位同一节目，节目删除后回退到当前节目或首项。切源关闭面板后定位首项，弹窗打开期间不抢焦点。移除收藏中最后一个正在全屏播放的节目时回到空收藏列表。

电视在确认频道前保持当前直播，避免浏览过程中反复建流。

电视侧栏使用单行频道和深色半透明背景。底栏播放按钮使用实心图标，已收藏频道使用暖金色实心星标。

播放器界面由 PlayerScreen 组合视频和设置，CinemaPlayerScreen 为手机和电视提供共享的全屏控件，ChannelList 负责频道列表和遥控器焦点。

## 手动检查应用更新

手机轻点播放画面，电视按确认键或 INFO 唤出底栏，从「更多 → 设置 → 检查应用更新」进入更新界面。「更多」中的「刷新」用于更新节目列表。应用更新检查通过 HTTPS 读取以下格式的清单。

```json
{"versionCode": 13, "versionName": "1.2.9", "apkUrl": "IPTV-release-v1.2.9-20261106150000.apk"}
```

运行 `./gradlew :app:assembleRelease` 会自动从打包结果生成 `app/build/outputs/update/release/latest.json`。先上传同次构建的 Release APK，再上传 JSON 到同一个 HTTPS 目录。生成的 `apkUrl` 是实际 APK 文件名，应用以接口地址为基准解析，也接受完整 HTTPS 下载地址。Debug 构建有独立的 `outputs/update/debug/latest.json`。

更新说明写入 `app/release-notes/<versionName>.txt`。构建会将说明放入 APK，并写入更新清单的可选 `releaseNotes` 字段。检查到新版时显示新版说明，已是最新或检查暂时失败时显示 APK 自带的本版说明。

打包时用 `-PAPP_UPDATE_MANIFEST_URL=https://example.com/iptv/latest.json` 指定接口，或将同名属性放入本机 Gradle 配置。未配置时，点击检查会显示提示。每次发布递增 APK 的 `versionCode`，保持发行签名一致。

应用按 `versionCode` 比较，确认后通过 HTTPS 下载到私有缓存，上限 256 MiB。校验包名、版本、最低 Android 版本及签名后打开系统安装界面。首次安装可能需要允许 IPTV 安装未知来源应用，授权返回后继续。
