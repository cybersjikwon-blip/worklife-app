package kr.worklife.bg

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import kr.worklife.R
import kr.worklife.data.Repo
import java.time.LocalDate
import java.util.Locale

/**
 * 퇴근 음성 안내 — 사용자가 준 녹음(res/raw/voice_checkout) 재생
 *  - 녹음이 실패하면 기기 TTS("퇴근합니다~!"), 그것도 안 되면 알림음
 *  - 진동 모드에서도 재생(기본 켬, 설정에서 끌 수 있음). 완전 무음 모드는 '진동 모드에서도 재생'이 켜져 있으면 재생
 *  - 백그라운드에서 퇴근 감지 직후 앱이 정리되지 않게 재생 동안 잠깐 깨어 있음(최대 10초)
 *  - 재생했는지/왜 안 했는지 근무기록 '기록 이력'에 남김
 */
object Voice {
    const val CHECKOUT_TEXT = "퇴근합니다~!"
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
    fun isOn(ctx: Context) = Repo.get(ctx).voiceOn
    fun set(ctx: Context, on: Boolean) { Repo.get(ctx).voiceOn = on; if (on) play(ctx, force = true, why = "켜기 미리 듣기") }
    fun inQuiet(ctx: Context) = prefs(ctx).getBoolean("voiceInQuiet", true)
    fun setInQuiet(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("voiceInQuiet", v).apply() }

    fun checkout(ctx: Context) {
        if (!isOn(ctx)) { log(ctx, "퇴근 음성 꺼져 있음"); return }
        play(ctx, force = false, why = "퇴근")
    }

    fun test(ctx: Context) = play(ctx, force = true, why = "테스트")

    private fun log(ctx: Context, msg: String) = runCatching { Repo.get(ctx).logDay(LocalDate.now(), msg) }

    fun play(ctx: Context, force: Boolean, why: String) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (!force && mode != AudioManager.RINGER_MODE_NORMAL && !inQuiet(app)) {
            log(app, "퇴근 음성 생략 (" + (if (mode == AudioManager.RINGER_MODE_VIBRATE) "진동" else "무음") + " 모드)"); return
        }
        // 재생 동안 잠깐 깨어 있기 (백그라운드에서 퇴근 감지 직후에도 끝까지 나오게)
        val wl = runCatching {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "worklife:voice").apply { acquire(10_000) }
        }.getOrNull()
        val done = Runnable { runCatching { if (wl?.isHeld == true) wl.release() } }
        main.post {
            runCatching { player?.release() }; player = null
            val attrs = AudioAttributes.Builder()
                // 진동 모드에서도 들리게 '내비 안내' 용도 (미디어 볼륨을 따름)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val ok = runCatching {
                val afd = app.resources.openRawResourceFd(R.raw.voice_checkout)
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.setOnCompletionListener { runCatching { it.release() }; if (player === it) player = null; done.run() }
                mp.setOnErrorListener { p, _, _ -> runCatching { p.release() }; if (player === p) player = null; speakTts(app, attrs, done); true }
                mp.prepare()
                // 미디어 볼륨이 0이면 안 들림 → 기록에 남겨 원인을 알 수 있게
                if ((am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 1) == 0) log(app, "퇴근 음성: 미디어 볼륨이 0이에요")
                mp.start()
                player = mp
            }.isSuccess
            if (ok) log(app, "퇴근 음성 재생 ($why)") else speakTts(app, attrs, done)
            main.postDelayed(done, 10_000)
        }
    }

    /** 녹음 재생 실패 시 기기 음성 */
    private fun speakTts(app: Context, attrs: AudioAttributes, done: Runnable) {
        runCatching { tts?.shutdown() }
        var engine: TextToSpeech? = null
        engine = runCatching {
            TextToSpeech(app) { st ->
                val e = engine
                if (st != TextToSpeech.SUCCESS || e == null || e.setLanguage(Locale.KOREAN) < 0) { beep(app); log(app, "퇴근 음성: 알림음으로 대신"); done.run(); return@TextToSpeech }
                e.setPitch(1.7f); e.setSpeechRate(1.08f); e.setAudioAttributes(attrs)
                e.speak(CHECKOUT_TEXT, TextToSpeech.QUEUE_FLUSH, Bundle(), "worklife")
                log(app, "퇴근 음성: 기기 음성으로 재생")
                main.postDelayed({ runCatching { e.shutdown() }; done.run() }, 6_000)
            }
        }.getOrNull()
        if (engine == null) { beep(app); done.run() }
        tts = engine
    }

    private fun beep(ctx: Context) {
        runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
    }
}
