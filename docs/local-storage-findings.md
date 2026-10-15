# Xiaomi 14 本地录音与转写调查

日期：2026-10-15。范围：用户自有、已授权 USB 调试的 Xiaomi 14。

## 结论

已找到并实测成功一条不依赖逐条分享的本地批量读取路径：**shell 调用小米系统备份服务，让录音机在自身权限下导出转写元数据；shell 再读取 JSON 和原始音频。**

它与普通 APP 直接访问私有数据库不同。后续已实现 Shizuku 用户服务并通过本机读取与 WebDAV 上传联调，详见 [开发测试记录](TESTING.md)。以下保留数据入口调查证据。

## 环境

- 型号：Xiaomi 14，系统型号 23127PN0CC / houji。
- Android 16 / API 36；HyperOS OS3.0.307.0.WNCCNXM。
- 录音机：com.android.soundrecorder，7.8.9.9-643c0d7ef，versionCode 708099。
- `ro.build.tags=release-keys`，`ro.debuggable=0`，`ro.boot.flash.locked=1`。未执行解锁、root 或修改系统包操作。

## 本地存储定位

录音机安装包的 `com.android.soundrecorder.database.q` 继承 SQLiteOpenHelper，以数据库名 `record.db` 和数据库版本 28 初始化。结合运行时 `dataDir=/data/user/0/com.android.soundrecorder`，其预期数据库位置为：

```
/data/user/0/com.android.soundrecorder/databases/record.db
```

这是根据实际安装包的标准数据库初始化代码与运行时 dataDir 定位出的路径。ADB 对该路径返回 Permission denied，因此本次未直接复制或打开这个私有 SQLite 文件。

代码中已确认的表：

| 表 | 关键字段与用途 |
| --- | --- |
| records | file_path、file_name、duration、sha1 等录音元数据 |
| recognition_sentence | sha1、sentence、timeline、speaker、speaker_id、content；逐句转写 |
| realtime_recog_sentence_table | record_id、sentence、timeline、speaker、content；实时转写 |
| recognize_text / audio_recognize | 其他转写版本的数据结构 |
| summary | sha1、summary_result、state、summaryFlag；AI 摘要 |

常规录音目录 `/sdcard/Android/data/com.android.soundrecorder/files` 顶层有 99 个音频文件（84 MP3 + 15 M4A）。备份内另外包含其他目录的录音记录；逐条按备份的 filePath 检查后，103 条记录的文件都存在。

“转写保存在本地”不能单独证明语音识别的计算完全在本机完成。本次确认的是存储与导出能力。

## 普通 APP 的限制已实测

离线探测 APP 包名 `local.xiaomi.audiosync.probe`，运行 UID 10326。没有 INTERNET 权限。实际授予并核实：

```
miui.permission.ACCESS_RECORDS = granted
com.hyperos.permission.READ_RECORD = granted
android.permission.READ_MEDIA_AUDIO = granted
```

查询 records、recognize_text、audio_recognize、recognition_sentence、realtime_recog_sentence_table、summary 均返回 NULL cursor。录音机日志明确记录该 APP 的签名不合法，verifyRecorderProviderPermission=false。

代码确认 Provider 在执行查询前检查签名白名单；这不是少申请了一个普通运行时权限。MediaStore 音频只有 5 条，录音机目录记录为 0。探测 APP 无法列出录音目录，也无法直接打开已知音频路径。

因此需要 shell 用户服务等额外权限路径；仅在普通 APP 中申请“所有文件访问”不能解决私有数据库访问。

## 已通过的批量导出

录音机的 SoundRecorderBackupAgent：

1. 在 onCreate 中设置 `getExternalFilesDir("backup") + "/backup.json"`。
2. onFullBackup(feature=3) 跳过音频目录与图片附件。
3. 调用 SoundRecordBackupInfo.getInfo()，从自身数据库汇集录音、转写、摘要等数据。
4. 将结果以 JSON 写入并纳入备份。

实际生成路径：

```
/sdcard/Android/data/com.android.soundrecorder/files/backup/backup.json
```

通过 `app_process` 运行的辅助程序以 UID 2000：

```
state=0
idle=true
shell backup authorization acquired=true
exporting recorder metadata only, feature=3, includeApk=false
backup bytes=15596110
state after export=0
released=true
```

导出使用 MiuiBackup 的正式 Binder 接口，经过其现有 BACKUP 权限检查和 acquire/release；未改写签名校验、未修改录音机 APK、未运行恢复。元数据备份触发生成了上述临时 JSON。

备份包含 MIUI BACKUP 外层头、ANDROID BACKUP 头和 tar。tar 只有 4 个成员，未包含 MP3/M4A/WAV 或 APK。JSON 大小 15,588,884 字节。完整备份 SHA-256：

```
a1f1002c9f029265bf33849133cdefc6537c00f29a98cdad5ed36fd7908393d0
```

## 解析与配对结果

| 检查 | 结果 |
| --- | --- |
| 录音元数据 | 103 条，103 个不同 sha1 |
| 按 filePath 验证音频存在 | 103 / 103 |
| 转写片段 | 15,806 条 |
| 正文 content 字符总数 | 902,977 |
| lyric 序列化字符总数 | 2,635,906，含结构信息，不能作为纯正文字数 |
| 有转写的 recordSha1 | 83 个 |
| 能与音频元数据配对的转写标识 | 82 个 |
| 已配对转写片段 | 15,735 条 |
| 暂无匹配音频元数据的转写标识 | 1 个，对应 71 条片段，需保留并复核 |
| 有音频但无匹配转写 | 21 条，不应标为“全部同步成功” |
| 摘要元数据 | 80 条，其中 52 条有非空 summaryResult |

另有 4 条删除记录元数据，应独立处理，不自动还原或上传已删除音频。

`sentenceList` 包含 recordSha1、content、lyric、timeLine、speaker、speakerId 等字段；recordSha1 与录音元数据 sha1 关联。已抽样计算一个实际 MP3 的 SHA-1，确认与该音频的元数据匹配。

最长录音时长 9,080 秒（2:31:20），对应 663 条片段、44,685 个正文字符；最后片段的时间轴延伸至 9,079,600 ms。此证据支持批量导出覆盖长录音，而不是截取当前界面可见文字；未逐字审校识别准确率。

## APP 实现建议与未完成项

建议优先实现 `Kotlin APP → Shizuku UserService(shell UID) → MiuiBackup feature=3 → backup.json + 音频文件 → HTTPS 服务器`。先接通读取能力，再加 Room 同步状态和幂等上传。

重要实现边界：

- 调查之后已安装 Shizuku 13.6 并实测 UserService 的 BACKUP 权限、Binder 调用和文件读取；重启恢复及其他 HyperOS 版本仍待验证。
- Shizuku 不会让普通 APP 任意读取私有数据库；这里依赖的是已经验证的系统备份导出路径。
- 非 root 的 Shizuku 启动及重启后的恢复需要按实际版本验证，不能承诺永久免操作。官方手册说明无线调试启动方式及重启限制：https://shizuku.rikka.app/zh-hans/guide/setup/
- 系统备份可能短暂占用或停止录音机，应仅在未录制、备份服务空闲时执行，并保证 finally 释放服务。
- 此接口为小米实现，系统或录音机升级后要复验；应把导出读取封装为可替换适配层。
- 导出是整批元数据快照，上传可以增量；音频和文本分别记录摘要与状态，转写延迟出现或被修改后补传。
- 元数据中有缺失配对和删除记录，不能把 NULL、无转写或未配对一律当成成功。
- 服务器地址、鉴权和真实上传尚未验证。

## 复现工具

- `tools/probe/BackupProbe.java`：默认仅检查现有 shell 的服务授权；仅传入 `--export-metadata` 时导出固定录音机包的 feature=3 元数据，不覆盖已有输出。
- `sh tools/probe/build-backup-probe.sh`：编译 app_process 探测 JAR。
- `python3 tools/inspect_metadata_backup.py <备份文件>`：仅打印聚合统计，不输出录音或转写正文。
- `tools/probe/ProbeActivity.java`：普通 APP 权限验证及离线分享接收验证，保留作为对照，非最终方案。

分享路线只验证过一个 19 分钟样本的 8,270 字符文本接收和 14,009,760 字节音频文件的头部读取；不将其描述为整批同步实现。用户提出优先查本地存储后，已停止界面自动化，最终主方案转为系统备份接口。

## 收尾

探测 APP 已卸载；手机上由本次调查生成的元数据备份、备份 JSON 和辅助 JAR 已删除，未删除原始录音。电脑上的调查快照保留于 `/tmp/xiaomi-recorder-metadata.bak`，权限设为 0600，没有放入项目或上传服务器。辅助程序编译通过，解析脚本已用真实备份执行验证。
