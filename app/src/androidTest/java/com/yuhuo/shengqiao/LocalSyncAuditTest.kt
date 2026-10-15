package com.yuhuo.shengqiao

import androidx.test.platform.app.InstrumentationRegistry
import com.yuhuo.shengqiao.data.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in audit of current destination's local receipts. No network or source writes. */
class LocalSyncAuditTest {
    @Test fun auditCurrentDestinationReceipts() {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditLocalSync") == "true")
        val config = Settings(ins.targetContext).load()
        Store(ins.targetContext).use { store ->
            val records = store.recordings()
            val marks = store.marks(config.destination)
            val states = records.map { store.state(it, marks[it.id] ?: SyncMark(), config.obsidianDestination) }
            val report = JSONObject().put("recordings", records.size)
                .put("withTranscript", records.count { it.transcript.isNotBlank() })
                .put("missingTranscript", records.count { it.transcript.isBlank() })
                .put("audioHashMatches", records.count { it.contentSha256.isNotEmpty() && marks[it.id]?.audio == it.contentSha256 })
                .put("transcriptHashMatches", records.count { it.transcript.isNotBlank() && marks[it.id]?.text == it.transcriptHash })
                .put("synced", states.count { it == "已同步" })
                .put("failed", states.count { it == "同步失败" })
                .put("other", states.count { it != "已同步" && it != "同步失败" })
            File(ins.targetContext.filesDir, "sync-count-audit.json").writeText(report.toString())
            println("LOCAL_SYNC_RECEIPTS $report")
        }
    }
}
