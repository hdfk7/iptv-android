# IPTV 手机与 TV 全量回归

使用手机真机和 TV 真机或模拟器。从项目根目录运行，准备 ADB、Python 3.10+、Pillow、FFmpeg 和 Android SDK Build Tools。手机用点按、滑动和输入框，TV 用遥控。所有业务数据通过 App UI 建立。ADB 只用于模拟输入、读取状态及建立测试网络。

## 1. 准备

运行 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease`。两台设备先通过安装界面装好同包名、同正式签名的旧版。新版 versionCode 必须更高。旧版 App 能正常访问的更新清单应指向本轮候选正式包。

在系统临时目录创建独立的 `iptv-qa-<唯一编号>` 子目录，设置 `IPTV_QA_OUTPUT` 指向该目录，在其中新建空文件 `.iptv-qa-run`。设 `PYTHONDONTWRITEBYTECODE=1`。旧版和新版 APK 放在临时目录外。

将自制的动态测试视频放在本轮目录，并用 FFmpeg 生成同名 TS 分片。启动 `python -B qa/fixture_server.py --media <临时目录中的MP4>`。用空闲端口建立两端的测试转发，默认 `adb -s <序列号> reverse tcp:18764 tcp:18764` 和 `adb -s <序列号> reverse tcp:18766 tcp:18766`。前者用于测试源，后者用于 HTTP 代理。端口调整时同步传给各脚本。

## 2. 两台设备先在旧版 App 准备数据

```powershell
$upgrade = @('--phone', $phone, '--tv', $tv, '--old-apk', $oldApk, '--new-apk', $newApk, '--build-tools', $buildTools)
python -B qa/local_upgrade.py --phase baseline @upgrade
```

脚本先验证两端旧版的版本和 APK 哈希，再依次在 App 里添加、启用测试源、播放、收藏，采集订阅、收藏和最近观看。两端都准备好后才生成配对基线。中途失败时先核对残留的测试数据，再重跑基线。

## 3. 从旧版 App 发起升级

```powershell
python -B qa/local_upgrade.py --phase upgrade @upgrade
```

从旧版 App 检查更新、下载，按需授权并在系统安装器确认。系统要求人工操作时，完成后用相同参数继续。升级脚本核验配对基线、安装版本与 APK 哈希，再对比原订阅、收藏和最近观看。两端通过后开始新版回归。

## 4. 新版功能和界面回归

候选包须与本地 Release 元数据指向的包一致。通过正常界面或旋转手机切换方向。TV 导航用 App 选好含多频道和分组的源。运行：

```powershell
python -B qa/release_ui.py --device phone --serial $phone --orientation portrait
python -B qa/release_ui.py --device phone --serial $phone --orientation landscape
python -B qa/release_ui.py --device tv --serial $tv
python -B qa/tv_navigation.py --serial $tv
python -B qa/tv_exit.py --serial $tv
python -B qa/controlled_ui.py --phase hls --device tv --serial $tv
```

| 场景 | 验收 |
| --- | --- |
| 界面 | 播放页、底栏、频道分组、更多、节目单、设置、更新、源管理和收藏。核对对齐、长名称、滚动、键盘遮挡与横屏按钮可达。 |
| TV 焦点 | 首次焦点、移动、高亮、确认与返回。节目单关闭后恢复原频道。分组下键定位当前频道或首项。关闭表单键盘后仍可用方向键。 |
| 播放与设置 | 首帧、暂停和恢复、换台、重连、切线、音量、启动频道和铺满。TV 先关闭浮层，首次返回提示，2 秒内再次返回退出。 |
| 数据功能 | 新增、编辑、取消及确认删除订阅，收藏、取消收藏和最近观看。 |
| 线路与缓存 | 验证首线 404、HLS 分片挂起和备用线路出画。用 `/control?mode=release-offline` 模拟列表和 XMLTV 503，经 App 刷新、重开核对缓存，随后切回 `/control?mode=ok`。编辑 URL 后核对缓存归属。 |
| 更新与键盘 | 核对当前版本、版本说明。安全键盘通过系统状态或真机实际输入确认。 |

两端分别运行 `controlled_ui.py --phase proxy-guide --device phone|tv --serial <序列号>`。检查代理及源站请求，核对跨入当天的节目从 00:00 显示、跨到次日的节目保留，以及当前和下一节目。手机从最近任务划掉 IPTV，TV 经系统应用详情停止 IPTV，再从桌面打开。运行 `controlled_ui.py --phase after-restart --device phone|tv --serial <序列号> --old-pid <之前输出的PID>` 验证冷启动、代理配置保留及新请求。表格中其余项目逐条走界面。

## 5. 汇报与清理

先核对结果，按设备整理通过、失败和待验证项。再通过系统设置的 **应用 → IPTV → 存储 → 清除数据** 恢复两端 IPTV 的刚安装状态。这会清除 IPTV 的原有和测试数据，保留设备上的最新正式版及主机的最新正式 APK。仅操作本轮指定设备上的 IPTV。手机系统全局代理和其他 App 保持原样。

停止本轮测试服务，并撤销本轮建立的端口转发。执行 `python -B qa/cleanup.py --phone $phone --tv $tv --new-apk $newApk`。脚本检查两台的最新包、默认订阅、空收藏及代理关闭，清理设备临时 UI 树和主机临时目录里的截图、视频、日志及检查点。最终结论在会话中汇报。

可选的换台耗时测试使用单独的性能环境和启用 `APP_DEBUG_SWITCH` 的签名 Release，运行 `python -B qa/release_tune.py --serial <TV序列号> --rounds 40`。
