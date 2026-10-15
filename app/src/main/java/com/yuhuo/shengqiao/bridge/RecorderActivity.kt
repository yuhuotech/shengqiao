package com.yuhuo.shengqiao.bridge

/** A resident ASR notification is not evidence of an active recording/playback. */
object RecorderActivity {
    fun blockingService(dump: String): String? {
        var component: String? = null
        for (line in dump.lineSequence()) {
            if (line.trimStart().startsWith("* ServiceRecord{")) {
                component = Regex("com\\.android\\.soundrecorder/([A-Za-z0-9_.$]+)").find(line)?.groupValues?.get(1)
            }
            if (component != null && Regex("\\bisForeground=true\\b").containsMatchIn(line)) {
                // AsrService can retain its foreground notification after playback ends.
                // All other foreground recorder services remain protected.
                if (component != ".newrecognize.service.AsrService" &&
                    component != "com.android.soundrecorder.newrecognize.service.AsrService") return component
            }
        }
        return null
    }
}
