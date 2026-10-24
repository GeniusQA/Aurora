package com.music.spotui.di

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * The audio focus this app never asked for.
 *
 * Playback happens inside a WebView, in the page's own media element, and on some ROMs (MIUI /
 * HyperOS reported) a WebView's audio that no app ever asked focus for is played into a session
 * the system keeps ducked or silent — the page really plays (its clock advances, its element sits
 * at its own level) and nothing is heard, and coming back from the background the sound is gone
 * while the player still says it plays.
 *
 * Asked for while this phone plays, given back when it stops, as any music app does.
 * [SpeakerKeepAlive] deliberately takes none: it writes silence, and must never make another
 * app stop.
 */
object AudioFocus {
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** [playingHere]: music plays on this phone (not driven on a Connect device). */
    fun set(playingHere: Boolean) = main.post {
        if (playingHere) take() else give()
    }

    private fun take() {
        if (request != null) return
        val am = Graph.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val r = runCatching {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                // A duck would be applied under a notification or a call: this is music, it is
                // either playing or paused, so it is not made quieter behind the user's back.
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { change ->
                    // A real loss (a call, another player that took it): the focus is given back
                    // and taken again on the next play, instead of being held against the system.
                    if (change == AudioManager.AUDIOFOCUS_LOSS) main.post { give() }
                }
                .build()
        }.getOrNull() ?: return
        val granted = runCatching { am.requestAudioFocus(r) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        request = if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) r else null
    }

    private fun give() {
        val r = request ?: return
        request = null
        val am = Graph.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.abandonAudioFocusRequest(r) }
    }
}
