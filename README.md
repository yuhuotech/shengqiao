# 小米声桥：小米录音与转写同步

更新日期：2026-10-15。小米声桥 Android APP 已开发，并通过 USB 安装到 Xiaomi 14 完成核心实机联调。

## 使用与开发

- 手机端：Kotlin 原生应用，包名 `com.yuhuo.shengqiao`，Android 13+，优先适配实测的 Xiaomi 14 / HyperOS 3。
- 读取：官方 Shizuku 用户服务以 shell 身份调用小米本地备份，批量取得已有转写并读取音频。
- 同步：WebDAV / NAS，支持连接测试、一键同步、单条同步、停止、失败重试、文本更新补传和远程完成标记核对。
- 本地：SQLite 保存每条录音及分项同步状态；提供搜索、筛选、转写预览；凭据通过 Android Keystore 加密。
- 界面：支持跟随系统、深色和浅色；首页同步面板随滚动收起，搜索与筛选吸顶，顶部按钮可手动展开。详情头部也可收展，提供播放 / 暂停、停止和拖动进度条。
- 统计：“已同步”包含音频已完成且当前没有转写的录音，另外标出缺少转写的数量；后续有新转写仍会变为待更新并补传。
- 身份与改名：实际文件 SHA-256 判重，名称变化保留转写、稳定 ID 与同步记录；远程音频和转写通过 WebDAV MOVE 改名，中断可恢复，不因改名重复上传。
- 双目录：主目录默认 `/shengqiao`；另向 `/obsidian/小米录音/YYYYMM/录音名.md` 导出完整 Markdown，两个目录可改并自动创建。Markdown 包含真实录音日期属性，便于 Obsidian 倒排。
- 图标：已采用用户要求的简洁音频符号、渐变和轻微材质立体感版本，见 `assets/brand/`。

配置说明见 [WebDAV / NAS 使用文档](docs/WEBDAV.md)。
验证结果见 [测试记录](docs/TESTING.md)。新版安装包位于 `dist/shengqiao-0.4.1-debug.apk`。Obsidian 排序见 [排序说明](docs/OBSIDIAN.md)。

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -s <真实手机序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK 使用本机 Android 调试签名，可用于本次 USB 安装验证。正式分发前应自行配置长期保管的发布签名，后续更新必须保持签名一致。

## 需求与当前判断

保留小米系统录音机的使用习惯，由独立 Android APP 发现录音及其已有转写，一键上传指定服务器，并在手机记录每项同步状态。

**已通过 ADB 实测批量导出本机转写及录音元数据，并验证全部 103 条录音记录的音频文件存在。** 不需要逐条分享、手动导入或服务器重新转写。APP 已实现 Kotlin + Shizuku 的 shell 用户服务：调用小米本地备份接口导出转写，再读取原始音频上传 WebDAV。

已通过 Shizuku 手机端读取、长短音频上传、重复同步、中断重试和前台服务联调，详见测试记录。普通 APP 直接读取仍被系统沙箱和录音机签名白名单阻止。

完整证据和限制见 [本地存储调查记录](docs/local-storage-findings.md)。

## 当前实机证据

| 检查项 | 当前结果 |
| --- | --- |
| USB 设备识别 | macOS USB 注册表识别到 Xiaomi 14 |
| USB 接口 | 已连接并授权 ADB，所有手机操作均指定真实设备序列号 |
| ADB 列表 | 真实 Xiaomi 14 与模拟器同时存在，已明确区分 |
| 工具 | 已定位现有 SDK：`/Users/hongmw/development/android-sdk`；ADB 不在当前 PATH |
| 系统、录音机版本 | Android 16 / API 36，HyperOS OS3.0.307.0.WNCCNXM；录音机 7.8.9.9-643c0d7ef |
| 普通 APP 读取 | 已安装探测 APP 实测，授予读取权限后 Provider 仍返回 NULL，日志明确签名不合法 |
| 本地转写导出 | shell 正常获得 MiuiBackup 服务授权，feature=3 导出成功，备份不含音频或 APK |
| 结果 | 103 条音频元数据、15,806 条转写片段、902,977 个正文字符；82 条录音匹配到转写 |

调查中另外下载了一份 Google 官方 Platform Tools 到 `/tmp/xiaomi-audio-tools/platform-tools`。实机调查包括临时探测 APP、权限授权、界面分享验证及本地元数据备份；未上传私人录音或文本到远程服务器，未执行恢复操作。

## 后续验证

1. 0.2.0 已完成内容身份、改名和恢复测试；真实私人音频的新增改名测试仍需允许手机安装测试辅助 APK。详见测试记录。
2. 实测断开 USB、重新启动手机、后台调度和服务被终止后的恢复；明确所需用户操作。
3. 接入用户提供的实际 NAS，验证其证书、鉴权及 MOVE 行为；本机 WebDAV 测试不能代替具体 NAS 验证。

## 不同检查结果对应的实现路径

| 实际情况 | 可实现的体验 | 限制 |
| --- | --- | --- |
| 本机已验证：Shizuku 调用小米元数据备份并读取音频 | 授权后直接一键同步，不逐条分享 | 系统或录音机升级需复验 |
| 音频和转写都在可授权共享目录 | 首次授权目录，随后扫描并一键同步 | 需验证文本格式、稳定标识及延迟生成 |
| 音频在 MediaStore，转写有可供第三方读取的 Provider | 授权后自动发现并一键同步 | 需验证接口所需权限和版本稳定性 |
| 转写只能通过录音机分享或导出 | 分享至本 APP，或导出到已授权目录后同步 | 增加人工分享/导出步骤，不是完全自动读取 |
| 转写仅在私有数据库，无可读接口 | 普通 APP 无法直接扫描已有转写 | 可改为上传音频后由服务器重新转写，但结果不等同于小米原文 |
| 需要 shell、Shizuku 或 root | 作为额外权限方案单独验证 | shell/Shizuku 不自动获得私有数据库访问权；不能承诺授权一次后永久可用 |

## 建议的同步设计

- 手机端可采用 Kotlin 原生实现，读取层与同步层分开，便于适配小米版本变化。
- 使用本地 Room/SQLite 保存来源标识、音频摘要、转写摘要、服务器记录 ID、最近成功时间和错误原因。音频和转写分别记录状态。
- 页面至少区分“未同步、同步中、仅音频已同步/等待转写、全部已同步、同步失败、源内容已更新”。
- 首次同步后转写才生成或被编辑，应继续补传；不能仅按文件名判断是否已同步。
- 服务器采用 HTTPS 鉴权接口，支持幂等提交与校验确认。只有服务器确认接收成功，手机才标记相应部分已同步；超时后重试不能重复创建记录。
- 一键同步属于用户主动任务；定时扫描与重试可使用 WorkManager，具体长任务机制按系统版本适配，并实测 HyperOS 后台行为。后台调度不能视为实时保证。
- 正在录制或仍在变化的文件应等待稳定后再上传；重命名不应造成重复上传，源文件删除不应自动删除服务器副本。
- 卸载或清除应用数据会丢失本地身份映射；来源标识仍相同时可核对原远程目录，来源标识已变化时不保证自动找回旧目录。

## 官方依据

- [Android 共享音频访问与 MediaStore](https://developer.android.com/training/data-storage/shared/media)：第三方音频访问需要相应权限；具体目录是否进入索引仍需实机检查。
- [Android 应用专属存储](https://developer.android.com/training/data-storage/app-specific)：其他应用不能直接读取内部私有文件。
- [所有文件访问权限的范围](https://developer.android.com/training/data-storage/manage-all-files)：此权限不等于可读取其他应用的私有数据。
- [ContentProvider 声明与访问控制](https://developer.android.com/guide/topics/manifest/provider-element)：是否导出、读取权限和临时 URI 授权共同决定可访问性。
- [Android 后台持久任务](https://developer.android.com/develop/background-work/background-tasks/persistent)：WorkManager 用于持久任务调度。

可行性判断以当前真机和录音机版本为范围。真实 NAS 地址和认证方式尚未提供；电脑上的 WebDAV 测试服务不能替代用户 NAS 的联调。
