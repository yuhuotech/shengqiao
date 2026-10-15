# Shizuku's UserService binder references classes by name; keep entry points.
-keep class dev.rikka.shizuku.** { *; }
-keep interface dev.rikka.shizuku.** { *; }
-keep class com.yuhuo.shengqiao.bridge.IRecorderBridge { *; }
-keep class com.yuhuo.shengqiao.bridge.IRecorderBridge$* { *; }
# Keep manifest entries
-keep public class com.yuhuo.shengqiao.SyncApp
-keep public class com.yuhuo.shengqiao.MainActivity
-keep public class com.yuhuo.shengqiao.sync.SyncService
-keep public class com.yuhuo.shengqiao.sync.AutoSyncJobService
