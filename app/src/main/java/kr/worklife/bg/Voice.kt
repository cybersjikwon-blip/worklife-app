package kr.worklife.bg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kr.worklife.data.Repo
import java.util.Locale

/**
 * 퇴근 음성 안내 ("퇴근합니다~!")
 *  - 기기 TTS(삼성/구글 한국어)를 높은 톤·살짝 빠르게 → 귀여운 목소리. 여성 음성이 있으면 우선
 *  - 무음·진동 모드면 말하지 않음 (회의 중 등)
 *  - 한국어 음성이 없거나 TTS가 실패하면 알림음으로 대신
 */
object Voice {
    const val CHECKOUT_TEXT = "퇴근합니다~!"
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    fun isOn(ctx: Context) = Repo.get(ctx).voiceOn
    fun set(ctx: Context, on: Boolean) { Repo.get(ctx).voiceOn = on; if (on) speak(ctx, "퇴근할 때 알려드릴게요~", force = true) }

    fun checkout(ctx: Context) { if (isOn(ctx)) speak(ctx, CHECKOUT_TEXT) }

    fun speak(ctx: Context, text: String, force: Boolean = false) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        if (!force && am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        main.post {
            runCatching { tts?.stop(); tts?.shutdown() }
            var engine: TextToSpeech? = null
            val release = Runnable { runCatching { engine?.shutdown() }; if (tts === engine) tts = null }
            engine = runCatching {
                TextToSpeech(app) { st ->
                    val e = engine
                    if (st != TextToSpeech.SUCCESS || e == null) { beep(app); release.run(); return@TextToSpeech }
                    val r = e.setLanguage(Locale.KOREAN)
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) { beep(app); release.run(); return@TextToSpeech }
                    runCatching {
                        e.voices?.filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
                            ?.sortedByDescending { v -> val n = v.name.lowercase(); (if ("female" in n || "-f" in n || "smtf" in n) 2 else 0) + v.quality / 1000 }
                            ?.firstOrNull()?.let { e.voice = it }
                    }
                    e.setPitch(1.7f)
                    e.setSpeechRate(1.08f)
                    e.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { main.post(release) }
                        @Deprecated("old api") override fun onError(id: String?) { main.post(release) }
                    })
                    if (e.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "worklife") != TextToSpeech.SUCCESS) { beep(app); release.run() }
                }
            }.getOrNull()
            if (engine == null) { beep(app); return@post }
            tts = engine
            main.postDelayed(release, 12_000) // 무슨 일이 있어도 정리
        }
    }

    private fun beep(ctx: Context) {
        runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
    }
}
