package com.yuhuo.shengqiao.data

import android.os.ParcelFileDescriptor
import com.yuhuo.shengqiao.bridge.IRecorderBridge
import org.json.JSONObject
import java.io.File

data class LoadedSnapshot(val snapshot: Snapshot, val cached: Boolean)

object RecorderSnapshot {
    @Synchronized fun load(bridge: IRecorderBridge, previous: List<Recording>, directory: File): LoadedSnapshot {
        val cached = File(directory, "source-snapshot.json")
        fun prior(): LoadedSnapshot {
            val snapshot = if (cached.exists()) MetadataParser.parse(JSONObject(cached.readText()))
                else Snapshot(previous, 0, 0)
            return LoadedSnapshot(snapshot, true)
        }
        // Never start a system backup during recording/playback: it can stop Recorder.
        if (JSONObject(bridge.status()).optBoolean("recorderBusy")) return prior()
        val descriptor = try { bridge.exportMetadata() } catch (e: Exception) {
            // A recording may start between the status check and export's guard.
            if (bridge.asBinder().pingBinder() && JSONObject(bridge.status()).optBoolean("recorderBusy")) return prior()
            throw e
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            val raw = it.readText()
            val parsed = MetadataParser.parse(JSONObject(raw))
            val temporary = File(directory, "source-snapshot.partial")
            temporary.writeText(raw)
            android.system.Os.rename(temporary.path, cached.path)
            LoadedSnapshot(parsed, false)
        }
    }
}
