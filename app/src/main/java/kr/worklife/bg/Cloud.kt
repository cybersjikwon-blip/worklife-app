package kr.worklife.bg

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kr.worklife.data.Repo
import kr.worklife.data.RepoDump
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 서버(Firebase) 백업·복원
 *  - 구글 로그인 → Firestore users/{내 ID}/backup/… 에 기록 전체(표마다) 저장. 보안 규칙상 본인만 읽고 쓰기
 *  - 문서 1개 최대 1MB라서 표를 약 700KB씩 나눠 저장, manifest에 목록
 *  - 사진은 폰에 (Storage 미사용) → [내보내기 백업]으로 따로
 *  - google-services.json 없이 빌드됐거나 로그인 전이면 조용히 꺼져 있음 (앱 다른 기능에 영향 없음)
 */
object Cloud {
    private const val CHUNK = 700_000

    fun configured(ctx: Context) = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() || FirebaseApp.initializeApp(ctx) != null }.getOrDefault(false)

    fun user(ctx: Context) = if (configured(ctx)) runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() else null
    fun email(ctx: Context): String? = user(ctx)?.email

    private fun webClientId(ctx: Context): String? {
        val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
        return if (id == 0) null else ctx.getString(id)
    }

    /** 로그인 화면 Intent. null이면 이유 문자열 */
    fun signInIntent(ctx: Context): Pair<Intent?, String?> {
        if (!configured(ctx)) return null to "서버 설정(google-services.json)이 빠진 채로 빌드됐어요. GitHub Secrets의 GOOGLE_SERVICES_JSON을 확인해 주세요."
        val web = webClientId(ctx) ?: return null to "구글 로그인 정보가 없어요. Firebase에서 Google 로그인을 켠 뒤 google-services.json을 다시 받아 Secrets에 넣어 주세요."
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(web).requestEmail().build()
        return GoogleSignIn.getClient(ctx, gso).signInIntent to null
    }

    /** 로그인 결과 처리 → 성공 시 null, 실패 시 이유 */
    fun onSignInResult(ctx: Context, data: Intent?, done: (String?) -> Unit) {
        try {
            val acc = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val cred = GoogleAuthProvider.getCredential(acc.idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(cred)
                .addOnSuccessListener { done(null) }
                .addOnFailureListener { done("로그인 실패: ${it.message}") }
        } catch (e: ApiException) {
            done(when (e.statusCode) {
                12501 -> "로그인을 취소했어요"
                10 -> "앱 서명(SHA-1)이 Firebase에 등록된 값과 달라요 (코드 10)"
                7 -> "인터넷 연결을 확인해 주세요"
                else -> "로그인 실패 (코드 ${e.statusCode})"
            })
        } catch (e: Exception) { done("로그인 실패: ${e.message}") }
    }

    fun signOut(ctx: Context) {
        runCatching { FirebaseAuth.getInstance().signOut() }
        runCatching { GoogleSignIn.getClient(ctx, GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut() }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("worklife", Context.MODE_PRIVATE)
    fun lastBackup(ctx: Context): String = prefs(ctx).getString("cloudLast", "") ?: ""

    /** 백업 (백그라운드 스레드에서). 결과 문구 */
    fun backup(ctx: Context): String {
        val u = user(ctx) ?: return "로그인이 필요해요"
        return runCatching {
            val repo = Repo.get(ctx)
            val col = FirebaseFirestore.getInstance().collection("users").document(u.uid).collection("backup")
            val parts = JSONArray()
            var rows = 0
            fun put(name: String, text: String) {
                text.chunked(CHUNK).forEachIndexed { i, c ->
                    val id = "${name}_$i"
                    Tasks.await(col.document(id).set(mapOf("d" to c)), 60, TimeUnit.SECONDS)
                    parts.put(id)
                }
            }
            put("prefs", RepoDump.prefsToJson(ctx).toString())
            for (t in RepoDump.TABLES) {
                val a = runCatching { RepoDump.tableToJson(repo, t) }.getOrNull() ?: continue
                rows += a.length()
                put("t-$t", a.toString())
            }
            val now = LocalDateTime.now().withNano(0).toString()
            Tasks.await(col.document("manifest").set(mapOf("parts" to parts.toString(), "at" to now, "rows" to rows), SetOptions.merge()), 60, TimeUnit.SECONDS)
            prefs(ctx).edit().putString("cloudLast", now).apply()
            "서버 백업 완료 · 기록 ${"%,d".format(rows)}건"
        }.getOrElse { "서버 백업 실패: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** 서버 백업 정보 (시각, 건수) — 없으면 null */
    fun remoteInfo(ctx: Context): Pair<String, Long>? {
        val u = user(ctx) ?: return null
        return runCatching {
            val d = Tasks.await(FirebaseFirestore.getInstance().collection("users").document(u.uid).collection("backup").document("manifest").get(), 30, TimeUnit.SECONDS)
            if (!d.exists()) null else (d.getString("at") ?: "") to (d.getLong("rows") ?: 0L)
        }.getOrNull()
    }

    /** 복원 (백그라운드 스레드에서): 서버 백업으로 폰 기록을 바꿈 */
    fun restore(ctx: Context): String {
        val u = user(ctx) ?: return "로그인이 필요해요"
        return runCatching {
            val col = FirebaseFirestore.getInstance().collection("users").document(u.uid).collection("backup")
            val man = Tasks.await(col.document("manifest").get(), 30, TimeUnit.SECONDS)
            if (!man.exists()) return "서버에 백업이 없어요"
            val ids = JSONArray(man.getString("parts") ?: "[]").let { a -> (0 until a.length()).map { a.getString(it) } }
            val texts = linkedMapOf<String, StringBuilder>()
            for (id in ids) {
                val d = Tasks.await(col.document(id).get(), 60, TimeUnit.SECONDS)
                texts.getOrPut(id.substringBeforeLast('_')) { StringBuilder() }.append(d.getString("d") ?: "")
            }
            val repo = Repo.get(ctx)
            var n = 0
            texts.forEach { (name, sb) ->
                if (name == "prefs") RepoDump.jsonToPrefs(ctx, JSONObject(sb.toString()))
                else if (name.startsWith("t-")) { val t = name.removePrefix("t-"); if (t in RepoDump.TABLES) { RepoDump.jsonToTable(repo, t, JSONArray(sb.toString())); n++ } }
            }
            "복원 완료 · ${man.getString("at")?.take(16)?.replace('T', ' ')} 백업 (${n}종)"
        }.getOrElse { "복원 실패: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** 자동 백업: 로그인돼 있고 마지막 백업이 20시간 넘었으면 (앱 열 때 백그라운드) */
    fun autoBackup(ctx: Context) {
        if (user(ctx) == null) return
        val last = lastBackup(ctx).let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        if (last != null && last.isAfter(LocalDateTime.now().minusHours(20))) return
        Thread { runCatching { backup(ctx) } }.start()
    }

    fun signInActivityResultOk(code: Int) = code == Activity.RESULT_OK || code == Activity.RESULT_CANCELED
}
