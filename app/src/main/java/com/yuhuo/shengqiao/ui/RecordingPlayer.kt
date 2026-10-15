package com.yuhuo.shengqiao.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors

/** Foreground detail player. Never copies the original file or holds its descriptor after setup. */
class RecordingPlayer(
    context: Context,
    private val openAudio: () -> ParcelFileDescriptor,
    private val changed: (State) -> Unit,
    private val failed: (String) -> Unit
) {
    data class State(val ready: Boolean = false, val loading: Boolean = false, val playing: Boolean = false,
                     val position: Int = 0, val duration: Int = 0)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) pause()
        }, handler).build()
    private var player: MediaPlayer? = null
    private var ready = false
    private var loading = false
    private var playWhenReady = false
    private var released = false
    private var focusHeld = false
    private val ticker = object : Runnable {
        override fun run() { publish(); if (player?.isPlaying == true) handler.postDelayed(this, 250) }
    }

    fun toggle() {
        if (released) return
        if (loading) { playWhenReady = !playWhenReady; publish(); return }
        if (!ready) { prepare(); return }
        if (player?.isPlaying == true) pause() else start()
    }
    private fun prepare() {
        val p = MediaPlayer()
        player = p; loading = true; playWhenReady = true
        p.setAudioAttributes(attributes)
        p.setOnPreparedListener {
            if (player !== p || released) return@setOnPreparedListener
            ready = true; loading = false
            if (playWhenReady) start() else publish()
        }
        p.setOnCompletionListener { abandonFocus(); handler.removeCallbacks(ticker); publish() }
        p.setOnSeekCompleteListener { publish() }
        p.setOnErrorListener { _, _, _ -> error(p, "无法播放这段音频，请检查文件及读取授权后重试"); true }
        publish()
        worker.execute {
            try {
                val descriptor = openAudio()
                handler.post {
                    descriptor.use {
                        if (released || player !== p) return@post
                        try { p.setDataSource(it.fileDescriptor); p.prepareAsync() }
                        catch (_: Exception) { error(p, "无法读取音频，请检查 Shizuku 授权及原文件") }
                    }
                }
            } catch (_: Exception) {
                handler.post { error(p, "无法读取音频，请先连接 Shizuku，并确认原文件仍存在") }
            }
        }
    }
    private fun start() {
        val p = player ?: return
        if (!ready || released) return
        if (!focusHeld) {
            focusHeld = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) { failed("暂时无法获得音频播放权限，请稍后再试"); publish(); return }
        }
        try {
            if (p.currentPosition >= p.duration) p.seekTo(0)
            p.start(); handler.removeCallbacks(ticker); ticker.run()
        } catch (_: Exception) { error(p, "播放失败，请重新打开录音后重试") }
    }
    fun pause() {
        playWhenReady = false
        if (ready && player?.isPlaying == true) player?.pause()
        abandonFocus(); handler.removeCallbacks(ticker); publish()
    }
    fun stop() {
        pause()
        if (ready) { player?.seekTo(0L, MediaPlayer.SEEK_CLOSEST); publish(position = 0) }
    }
    fun seekTo(position: Int) {
        if (ready) {
            player?.seekTo(position.coerceIn(0, player!!.duration).toLong(), MediaPlayer.SEEK_CLOSEST)
            publish(position)
        }
    }
    private fun publish(position: Int? = null) {
        if (!released) changed(State(ready, loading, ready && player?.isPlaying == true,
            if (ready) position ?: player!!.currentPosition else 0, if (ready) player!!.duration else 0))
    }
    private fun abandonFocus() { if (focusHeld) audio.abandonAudioFocusRequest(focus); focusHeld = false }
    private fun error(p: MediaPlayer, message: String) {
        if (released || player !== p) return
        player = null; ready = false; loading = false; playWhenReady = false
        handler.removeCallbacks(ticker); abandonFocus(); p.release(); publish(); failed(message)
    }
    fun release() {
        released = true; handler.removeCallbacks(ticker); abandonFocus()
        player?.release(); player = null; ready = false; loading = false; worker.shutdownNow()
    }
}
