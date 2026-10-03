# v0.1.3 当前开发机验收方案

> 历史候选/方案记录；最终 v0.1.3/code10 状态见 [发行收尾](V0.1.3-COMPLETION.md)。保留原包身份、失败和未执行项目，不将其改写为最终包通过。

本次为环境检查、官方资料核对与验收调整，**不是以下新增测试的执行报告**。
权威必要/可选清单见 [发行验收表](RELEASE-ACCEPTANCE-V0.1.3.md)。不改APK、不升SDK、不创建tag或Release。

## 1. 已核查环境

- Intel x86_64 Mac，macOS 26.6.2，8逻辑CPU，24GiB内存，项目所在卷约734GiB可用。
- FFmpeg/ffprobe已经安装；Android CLI、SDK、ADB、构建及长期Release签名工具已具备。
- API37 AVD：4GiB RAM、16GiB data分区，当前约14GiB可用；现有API36/API28各2GiB RAM、10GiB data分区。
- 当前ADB只连接API37模拟器，无物理设备。模拟器串行运行，不必同时开启三台或重装开发环境。
- SDK环境：`source /Users/macos/.config/android-dev/env.zsh`；CLI统一使用`--no-metrics`。
- 不触碰原v0.1.0包、现有真实浏览数据或未跟踪的`artifacts/`。变更系统设置的故障测试使用专用AVD/独立Debug包；升级测试使用有备份的正式包测试设备。

这些资源足够执行必需测试；不足的是测试脚本适配和具体证据，不是缺一批开发工具。

## 2. 最小补验队列

| 顺序 | 工作 | 本机方法 | 合格证据 |
|---|---|---|---|
| A | >1GiB直链 | 自生成合法MP4；流式Range服务器；API37实际暂停/续传/发布 | 文件>1GiB、完整SHA相同、Range偏移/If-Range正确、无重复追加/私有残片 |
| B | >30分钟有声HLS | 现有1801秒生成器、8767端口长片服务器、现有hlsLong验收 | 合法MP4、A/V轨/时长/时间轴、完整解码、开中尾可读、真实跨UID分享 |
| C | 当前候选后台15分钟 | 受控慢速服务器；Home+屏幕关闭；真实计时；另测HLS后台封装 | 901秒量级实际记录、字节/分片增长、最终完成或受限状态可解释；无假成功 |
| D | OS故障与冷恢复 | API36通知拒绝、缩短FGS超时；API37断网/Wi-Fi约束、强停/重启 | 正确状态/停止服务/可恢复检查点；返回应用后用户主动恢复；不承诺强停后后台运行 |
| E | 真实HTTPS小样本 | 最终签名APK，3个不同获授权作品、≥2独立环境，MP4/WebM/HLS | 登记许可、环境/API/WebView、日期、成品和源的校验/解码；只记录实际执行 |
| F | 实际覆盖升级 | code4→当前、已有code7→8证据、当前→下一候选；同证书、不卸载 | 文件/任务/书签/历史/主题/Wi-Fi偏好保留，schema迁移/备份有效 |
| G | 冻结 | 复用未受变更影响的129单元/216 API37/80 API28证据；受影响部分重测，最终构建/签名复核 | 关联源码提交与APK哈希，不把旧包或跳过项算通过 |

若改动下载引擎/存储/请求策略，G必须补相关完整回归；只改开发机服务器和文档不必为了数字重跑所有旧测试。
本表描述依赖顺序，不承诺固定工时。至少一个15分钟测试必须真实等待，不用快进/模拟时间替代。

## 3. 大文件与长视频：复用点及必须补的工具

### >1GiB直链

`tools/fixtures/serve_resume_fixture.py`目前`read_bytes()`加载全文件，且128MiB上限、256字节写块。
**不能直接拿它测1GiB，也不能只删除上限而继续全文件进内存。** 下一轮先把开发机服务器改为：

- 流式预计算SHA/强ETag，GET按磁盘seek及64KiB块读取；长度、Range、If-Range身份检查明确。
- 支持合法大文件及可控限速/错误Range/ETag变化；保持仅loopback、有限预算，不记录Cookie/敏感URL。
- 自生成可完整解码的音视频MP4（FFmpeg适当码率/噪声输入），实际检查文件超过1GiB。不能通过在小片后附加free box、随机字节或sparse洞满足“大视频”指标。
- 源文件+私有临时+公共成品+主机回传约需数GiB，当前空间足够；精确记录测试任务和文件名，只清理本任务。
- 暂停后检查字节冻结，恢复实际206；最终回传成品与源整文件SHA-256比较。先做短文件错误Range，避免每个安全拒绝都重复传1GiB。

现有`serve_video_fixture.py`的`/long.mp4`使用free box填充和限速：可做后台时长夹具，**不是大视频或长媒体样本**。

### >30分钟有声HLS

现有脚本`--long`固定生成1801秒、同一连续时间轴H.264/AAC、2秒TS片；不是>60分钟。
示例（输出目录必须不存在；媒体只留ignored build目录）：

```sh
cd /Users/macos/Code/PureBrowser
bash tools/fixtures/generate_hls_fixture.sh \
  --output app/build/reports/v0.1.3/long-hls-audio --long --channels 2
python3 tools/fixtures/serve_hls_fixture.py \
  --port 8767 --directory app/build/reports/v0.1.3/long-hls-audio \
  --max-seconds 3600 --max-requests 12000
```

服务器前台启动并记录会话，只停止本次启动的进程。专用AVD映射8767后，安装本次Debug和AndroidTest APK，运行：

```sh
adb -s "$SERIAL" reverse tcp:8767 tcp:8767
adb -s "$SERIAL" shell am instrument -w \
  -e class com.example.purebrowser.download.HlsProductAudit#longVideoSavesAndRealChooserRecipientReadsIdenticalMp4 \
  -e hlsLong true \
  io.github.jeckchen666.purebrowser.debug.test/androidx.test.runner.AndroidJUnitRunner
```

前置：`SERIAL`绑定专用测试设备、测试包已安装、系统chooser可用；此命令不是当前已通过的证据。
现有测试下载等待上限15分钟，长媒体时长与下载墙钟时长分开。下载快并不表示视频被截短。
测试已有开中尾取帧和真正跨UID读取SHA；还须把这个任务的公共MP4回传主机完整检查：

```sh
ffprobe -v error -show_streams -show_format -of json "$OWNED_OUTPUT_MP4"
ffmpeg -nostdin -v error -i "$OWNED_OUTPUT_MP4" \
  -map 0:v:0 -map 0:a:0 -f null -
shasum -a 256 "$OWNED_OUTPUT_MP4"
```

检查音/视频轨、完整解码、DTS解码顺序与呈现时间轴、两轨起点/终点差异，无异常漂移；B帧的packet PTS不要求按解码顺序单调。纯测试图+恒定正弦只能证明音轨存在及时间轴，不能证明口型同步；另用真实获授权有声样本人工听看开中尾（或补有对应视觉/声音节拍的合成夹具）。
>60分钟和物理设备复验列可选，不再强迫本次等待一小时；30分钟媒体基础验收仍保留。

## 4. 后台、网络、通知和系统限制

### 真正15分钟后台

可复用`BackgroundTransferAudit`的`background15=true`与`HlsProductAudit`的`hlsBackground=true`。
它们默认不执行，不能把全量套件中的跳过视为后台通过。受控限速确保任务测试结束前仍在传输；记录起止时间、屏幕状态、字节/片数。
推荐在API36专用AVD运行至少一次当前候选15分钟；HLS另跑后台封装/回到库播放。
Home与屏幕关闭不等于Doze。可以在测试时用macOS `caffeinate`避免主机睡眠，测试完终止本次持有；不永久改变系统电源设置。

### FGS超时：不必实等六小时

Android官方给出`data_sync_fgs_timeout_duration`测试开关。目标版本已有超时限制，在专用API36/37设备：

```sh
adb -s "$SERIAL" shell device_config get activity_manager data_sync_fgs_timeout_duration
# 保存上述原值，再为当前AVD临时缩短：
adb -s "$SERIAL" shell device_config put activity_manager data_sync_fgs_timeout_duration 60000
```

从前台由用户开始慢速任务，再Home退后台，观察实际`onTimeout`、任务解释、服务退出和无ANR/崩溃。继续留在前台会影响计时，不能当作超时验证。
**finally恢复原值：** 原值为null则`device_config delete activity_manager data_sync_fgs_timeout_duration`，否则put原值；记录实际命令权限/效果。若镜像拒绝设置，不能以直接调用回调替代“真实OS超时通过”。
必要时官方还提供`am compat enable FGS_INTRODUCE_TIME_LIMITS PACKAGE`给低target测试；当前target不需额外覆盖，不无故改变兼容开关。

### 通知拒绝

API36独立Debug包记录原授权/flags，再`pm revoke PACKAGE android.permission.POST_NOTIFICATIONS`测试。
用户仍可从前台开始下载；**通知拒绝本身不等于FGS必须启动失败**。要求下载状态可见、无崩溃、服务按规则收尾，不伪造“系统拒绝”。测试完恢复原授权/flags。
服务启动拒绝分开做定向故障注入及可实现的后台启动限制场景，别把通知权限测试偷换成启动限制测试。

### 网络/Wi-Fi/Doze

- 服务器中断/重启验证真实socket失败与安全恢复；策略fake验证Wi-Fi约束状态机，但不冒充真正网络切换。
- `adb reverse`是调试隧道，关Wi-Fi不一定断开它。网络专项用`10.0.2.2`端点配合实际网络状态观察，或明确截断服务器连接；记录NetworkCapabilities与真实请求，不凭开关按钮判断成功。
- 官方Emulator console `network speed/delay`仅作用于模拟的蜂窝/Ethernet，不保证覆盖Wi-Fi/ADB reverse；限速可放服务器，不能声称已模拟所有弱网。
- Doze可用官方`dumpsys deviceidle force-idle`，但要记录实际idle状态；先模拟离电/灭屏，结束`deviceidle unforce`与`dumpsys battery reset`并恢复所有网络设置。
- 进入Doze后不要求视频仍持续联网；正确停止/等待、完整性不损坏、用户返回后可解释恢复才是合格。模拟器不代表OEM杀后台。

## 5. 强停、重启、存储故障、升级

- 强停已经有独立进程实绩；冷恢复新增`adb reboot`，等boot完成，再主动打开应用/操作恢复。不得注册后台偷偷重启敏感下载。
- 普通进程死亡与用户强停分别登记。需要真实kill时使用可定位Debug PID/外部操作，不把单进程内直接调用`recover`当作真实死亡。
- 必需容量边界与临时写入/公共发布异常用手写fake/故障注入，检查非SUCCEEDED、检查点/待发布清理、不误删。其证据只称“注入通过”。
- 真正ENOSPC压力为可选：另建可弃用AVD，受控占位仅在本任务目录，不填当前主AVD/主机磁盘，不全局扫Downloads。失败后清指定文件并恢复，不以权限错误冒充磁盘满。
- 升级使用现有仓库外签名工具构建保留基线身份：旧包种入测试数据→`adb install -r`新包→校验；不要先卸载，也不要用降级安装现有用户设备。
- 下一候选versionCode必须>8，同签名覆盖。code7→8已有证据；code4→当前和后续候选链单独补齐，不能为了形式先制造一个v0.1.2发行。
- Release不可`run-as`是正常安全属性；需要内部验收信息可由受控instrumentation显式输出allowlist或读取本任务URI。测试APK不会作为发行附件，不能开启Release debug后声称产物相同。

## 6. 官方核对依据

通过Android CLI知识库和Android开发者原始文档核对。下面说明可用测试机制，不意味着项目已经通过它们。

- FGS超时与测试时间缩短： https://developer.android.com/develop/background-work/services/fgs/timeout
- Doze/Standby与测试退出/恢复： https://developer.android.com/training/monitoring-device-state/doze-standby
- Emulator console网络模拟的适用接口： https://developer.android.com/studio/run/emulator-console
- 通知授权拒绝与FGS关系： https://developer.android.com/develop/ui/views/notifications/notification-permission

最终报告必须清楚区分单元/fake、AVD系统实测、真实HTTPS、物理OEM、人工声画与多人试用；没有哪个类别可以冒充另一个类别。

## 本轮执行证据

现有Range服务器已流式改造并增加真实socket截断。实绩见V0.1.3-RC2-LOCAL-ACCEPTANCE.md；源码与APK进入rc.2/code9，修复后225项单批已通过，发行冻结前不发布。长片声画关系补验采用源/成品双轨解码哈希及所有包共同PTS偏移核对，真人主观听看不冒充自动化。
