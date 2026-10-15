package com.yuhuo.shengqiao

import com.yuhuo.shengqiao.data.MetadataParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetadataParserTest {
    private val id = "a".repeat(40)
    private fun record(key: String = id, name: String = "会议") = JSONObject()
        .put("sha1", key).put("displayName", name).put("filePath", "/storage/emulated/0/Android/data/com.android.soundrecorder/files/$name.mp3")
        .put("duration", 80).put("createTime", 1000).put("size", 100)
    private fun sentence(start: Long, content: String, key: String = id) = JSONObject()
        .put("recordSha1", key).put("content", content).put("timeLine", "[$start,${start + 500}]")
        .put("speaker", "说话人1").put("speakerId", 0).put("lyric", "[\"$content\"]")

    @Test fun `out of order segments stay linked and sorted with Unicode intact`() {
        val root = JSONObject().put("mRecordFileList", JSONArray().put(record()))
            .put("sentenceList", JSONArray().put(sentence(2000, "第二句"))
                .put(sentence(0, "第一句")).put(sentence(4, "游离文字", "b".repeat(40))))
        val result = MetadataParser.parse(root)
        assertEquals(1, result.recordings.size)
        assertEquals(1, result.orphanSentences)
        val r = result.recordings.single()
        assertTrue(r.transcript.indexOf("第一句") < r.transcript.indexOf("第二句"))
        assertEquals(0, JSONObject(r.transcriptJson).getJSONArray("sentences").getJSONObject(0).getLong("startMs"))
        assertTrue(r.transcript.contains("[00:00:02]"))
    }
    @Test fun `deleted and trash recordings are not resurrected by synchronization`() {
        val second = "b".repeat(40)
        val third = "c".repeat(40)
        val root = JSONObject().put("mRecordFileList", JSONArray().put(record()).put(record(second))
            .put(record(third).put("filePath", "/storage/emulated/0/Android/data/com.android.soundrecorder/files/.trash/x.mp3")))
            .put("mDeleteLocalRecordList", JSONArray().put(JSONObject().put("sha1", second)))
        assertEquals(listOf(id), MetadataParser.parse(root).recordings.map { it.id })
    }
    @Test fun `later transcription changes receipt while unchanged snapshot is stable`() {
        val root = JSONObject().put("mRecordFileList", JSONArray().put(record()))
        val first = MetadataParser.parse(root).recordings.single()
        assertEquals("", first.transcriptHash)
        root.put("sentenceList", JSONArray().put(sentence(0, "已转写")))
        val transcribed = MetadataParser.parse(root).recordings.single()
        assertNotEquals("", transcribed.transcriptHash)
        assertEquals(transcribed.transcriptHash, MetadataParser.parse(JSONObject(root.toString())).recordings.single().transcriptHash)
        root.getJSONArray("sentenceList").getJSONObject(0).put("content", "人工修订")
        assertNotEquals(transcribed.transcriptHash, MetadataParser.parse(root).recordings.single().transcriptHash)
        assertEquals(first.metadataHash, transcribed.metadataHash)
    }
    @Test fun `unexpected export format does not erase the previous local snapshot`() {
        assertThrows(IllegalArgumentException::class.java) { MetadataParser.parse(JSONObject()) }
    }
}
