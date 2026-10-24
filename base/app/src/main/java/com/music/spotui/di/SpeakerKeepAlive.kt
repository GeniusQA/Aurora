package com.music.spotui.di

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The "bop" between two songs on the phone's speaker: the Web Player closes its audio
 * stream at the end of a song and opens a new one for the next, and the phone powers
 * its amplifier down and up again in between. A silent stream of our own, kept open
 * while the music plays here (and a little after it stops), keeps the output awake
 * across the change. It asks for no audio focus and plays nothing audible.
 */
object SpeakerKeepAlive {
    private const val TAG = "MspPlayer"
    private const val RATE = 48_000
    /** Pauses and song changes shorter than this keep the stream (and the output) open. */
    private const val LINGER_MS = 15_000L

    /**
     * Off while "the same volume, sometimes no sound at all" is open. This is the only audio
     * track of ours that starts and stops with playback, and every start is a new USAGE_MEDIA
     * AudioTrack: the audio patch is reconfigured while Chromium's own output (an AAudio stream)
     * is running, and a sink that dies there looks exactly like the reports - the page's clock
     * advances in real time, level 1.0, not muted, `music=true`, the route on the speaker, and
     * nothing is heard until something recreates the sink. Its whole purpose is a cosmetic one
     * (a "bop" between two songs), so it is turned off for the test: true brings it back.
     */
    private const val ENABLED = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var track: AudioTrack? = null
    @Volatile private var feeder: Thread? = null
    private val release = Runnable { stop() }

    /** [playingHere]: music plays on this phone (not on a Connect device). */
    fun set(playingHere: Boolean) {
        if (!ENABLED) return
        main.post {
            if (playingHere) {
                main.removeCallbacks(release)
                start()
            } else if (track != null) {
                main.removeCallbacks(release)
                main.postDelayed(release, LINGER_MS)
            }
        }
    }

    private fun start() {
        if (track != null) return
        val size = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        runCatching { t.play() }
        // Where this app's sound actually goes. The status line lists the outputs that EXIST;
        // this is the route the framework really gave a track of ours, which the WebView's own
        // track shares as the same app and usage. "The page plays, nothing is heard" is settled
        // here: a route that is not the speaker says where the music went.
        Log.i(TAG, "keep-alive track: routed to ${describe(t.routedDevice)} session=${t.audioSessionId}")
        runCatching {
            t.addOnRoutingChangedListener(object : AudioRouting.OnRoutingChangedListener {
                override fun onRoutingChanged(router: AudioRouting?) {
                    Log.i(TAG, "keep-alive track: routed to ${describe((router as? AudioTrack)?.routedDevice)}")
                }
            }, main)
        }
        feeder = Thread({
            val zeros = ByteArray(size)
            // Blocking writes pace themselves on the output: no busy loop.
            while (track === t) if (t.write(zeros, 0, zeros.size) < 0) break
        }, "speaker-keepalive").apply { isDaemon = true; start() }
    }

    /** `type2(Xiaomi Speaker)` for the log: the device type and its name when it has one. */
    private fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "unknown"
        val name = runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
        return "type${device.type}${if (name.isBlank()) "" else "($name)"}"
    }

    private fun stop() {
        val t = track ?: return
        track = null
        feeder = null
        runCatching { t.pause(); t.flush(); t.stop() }
        t.release()
    }
}
