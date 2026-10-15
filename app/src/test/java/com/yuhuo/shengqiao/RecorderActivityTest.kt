package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.bridge.RecorderActivity
import org.junit.Assert.*
import org.junit.Test

class RecorderActivityTest {
    private fun service(component: String, foreground: Boolean) = """
      * ServiceRecord{123 u0 com.android.soundrecorder/$component c:com.android.soundrecorder}
        isForeground=$foreground foregroundId=1001
        startRequested=false
    """.trimIndent()

    @Test fun `resident ASR foreground notification does not block idle recorder export`() {
        val dump = service(".ai.airecorder.AIRecordService", false) + "\n" +
            service(".newrecognize.service.AsrService", true) + "\n" + service(".RecorderService", false)
        assertNull(RecorderActivity.blockingService(dump))
    }
    @Test fun `active recording stays blocked even when ASR is also present`() {
        val dump = service(".newrecognize.service.AsrService", true) + "\n" + service(".RecorderService", true)
        assertEquals(".RecorderService", RecorderActivity.blockingService(dump))
    }
    @Test fun `unknown foreground recorder and playback services stay protected`() {
        assertEquals(".PlaybackService", RecorderActivity.blockingService(service(".PlaybackService", true)))
        assertEquals(".ai.airecorder.AIRecordService", RecorderActivity.blockingService(service(".ai.airecorder.AIRecordService", true)))
    }
    @Test fun `fully qualified ASR service is treated identically`() {
        assertNull(RecorderActivity.blockingService(service("com.android.soundrecorder.newrecognize.service.AsrService", true)))
    }
    @Test fun `non foreground services and empty dump do not block export`() {
        assertNull(RecorderActivity.blockingService(service(".RecorderService", false)))
        assertNull(RecorderActivity.blockingService(""))
    }
}
