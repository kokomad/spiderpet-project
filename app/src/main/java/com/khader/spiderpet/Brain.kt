package com.khader.spiderpet

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

enum class Act { WANDER, INSPECT, OPEN, WEB, REST }

data class IconTarget(val label: String, val rect: Rect) {
    val cx: Float get() = rect.exactCenterX()
    val cy: Float get() = rect.exactCenterY()
}

data class Decision(
    val act: Act,
    val icon: IconTarget? = null,
    val nx: Float = 0.5f,
    val ny: Float = 0.5f,
    val say: String = "",
    val seconds: Float = 3f
)

data class BrainContext(
    val w: Int,
    val h: Int,
    val sx: Float,
    val sy: Float,
    val icons: List<IconTarget>,
    val webProgress: Float,
    val webEnabled: Boolean,
    val corner: Int,
    val hour: Int,
    val recent: List<String>,
    val openApps: Boolean
)

/** Offline fallback "brain" - used when AI is off, has no key, or is unreachable. */
object LocalBrain {
    private val chatter = listOf(
        "همم... ما هذا؟",
        "أحب هذه الزاوية",
        "سأنسج هنا قليلاً",
        "من يسكن هنا؟",
        "رائحة بيانات لذيذة",
        "لا تدعسني!"
    )

    fun decide(c: BrainContext): Decision {
        val r = Random.nextFloat()
        val wantWeb = c.webEnabled && c.webProgress < 1f
        val say = if (Random.nextFloat() < 0.18f) chatter.random() else ""
        return when {
            wantWeb && r < 0.24f -> Decision(Act.WEB, say = say)
            c.icons.isNotEmpty() && r < 0.64f -> {
                val ic = c.icons.random()
                val open = c.openApps && Random.nextFloat() < 0.06f
                val s = if (Random.nextFloat() < 0.35f) "أتفحّص «${ic.label}»" else say
                Decision(if (open) Act.OPEN else Act.INSPECT, ic, say = s, seconds = 2.5f + Random.nextFloat() * 3f)
            }
            r < 0.84f -> Decision(
                Act.WANDER,
                nx = 0.08f + 0.84f * Random.nextFloat(),
                ny = 0.08f + 0.84f * Random.nextFloat(),
                say = say
            )
            else -> Decision(Act.REST, seconds = 2f + Random.nextFloat() * 5f, say = say)
        }
    }
}

/** Gemini client (generateContent REST). Default model: gemini-3.8-flash */
object GeminiClient {
    private const val SYSTEM =
        "You control a small, realistic black widow spider that lives on an Android phone's home screen. " +
        "Each call, choose the spider's next behavior. Be natural: spiders are curious, skittish, love corners, " +
        "explore app icons, and spin webs. Reply with ONE JSON object only, no markdown: " +
        "{\"action\":\"wander|inspect_icon|open_icon|build_web|rest\",\"icon\":<index from list or -1>," +
        "\"x\":<0..1>,\"y\":<0..1>,\"seconds\":<2..8>,\"say\":\"<max 6 Arabic words or empty>\"}. " +
        "Rules: x,y are normalized screen coordinates (0,0 = top-left) used for wander. " +
        "inspect_icon only with a valid icon index. open_icon only if openAppsAllowed is true, and very rarely. " +
        "build_web only if webProgress < 1. Avoid repeating the recent actions. Stay inside the screen. " +
        "Keep 'say' empty most of the time; when used, make it short, playful and in Arabic."

    private fun post(apiKey: String, model: String, body: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 10000
            con.readTimeout = 15000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("x-goog-api-key", apiKey)
            con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            con.disconnect()
        }
    }

    private fun buildBody(system: String, user: String, thinking: Boolean): String {
        val gen = JSONObject().put("responseMimeType", "application/json")
        if (thinking) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))
                )
            )
            .put("generationConfig", gen)
            .toString()
    }

    private fun errorText(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            body.take(120)
        }
        return "HTTP $code: $msg"
    }

    private fun extractText(body: String): String {
        val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    /** Returns model text or throws IllegalStateException with a readable message. */
    private fun ask(apiKey: String, model: String, system: String, user: String): String {
        var res = post(apiKey, model, buildBody(system, user, true))
        if (res.first == 400) res = post(apiKey, model, buildBody(system, user, false))
        if (res.first !in 200..299) throw IllegalStateException(errorText(res.first, res.second))
        return extractText(res.second)
    }

    private fun stamp(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    fun ping(ctx: Context, apiKey: String, model: String): String {
        if (apiKey.isBlank()) return "أدخل مفتاح Gemini أولاً"
        return try {
            val t = ask(apiKey, model, "Reply with JSON only.", "{\"ping\":true} -> reply {\"ok\":true}")
            val s = "✓ الاتصال ناجح مع $model (${stamp()})"
            Prefs.setAiStatus(ctx, s)
            if (t.isBlank()) "$s — رد فارغ" else s
        } catch (e: Exception) {
            val s = "✗ ${e.message ?: "فشل الاتصال"}"
            Prefs.setAiStatus(ctx, s)
            s
        }
    }

    fun decide(ctx: Context, apiKey: String, model: String, c: BrainContext): Decision? {
        return try {
            val icons = JSONArray()
            c.icons.take(30).forEachIndexed { i, ic ->
                icons.put(
                    JSONObject().put("i", i).put("label", ic.label.take(30))
                        .put("x", round2(ic.cx / c.w)).put("y", round2(ic.cy / c.h))
                )
            }
            val cornerName = arrayOf("top-left", "top-right", "bottom-left", "bottom-right")[c.corner.coerceIn(0, 3)]
            val user = JSONObject()
                .put("screen", JSONObject().put("w", c.w).put("h", c.h))
                .put("spider", JSONObject().put("x", round2(c.sx / c.w)).put("y", round2(c.sy / c.h)))
                .put("icons", icons)
                .put("webProgress", round2(c.webProgress))
                .put("webEnabled", c.webEnabled)
                .put("webCorner", cornerName)
                .put("hour", c.hour)
                .put("openAppsAllowed", c.openApps)
                .put("recent", JSONArray(c.recent))
                .toString()
            val text = ask(apiKey, model, SYSTEM, user)
            val a = text.indexOf('{')
            val b = text.lastIndexOf('}')
            if (a < 0 || b <= a) throw IllegalStateException("رد غير مفهوم")
            val j = JSONObject(text.substring(a, b + 1))
            val d = parse(j, c)
            Prefs.setAiStatus(ctx, "✓ آخر قرار من الذكاء الاصطناعي: ${j.optString("action")} (${stamp()})")
            d
        } catch (e: Exception) {
            Prefs.setAiStatus(ctx, "✗ ${e.message ?: "فشل"} — يعمل العقل المحلي مؤقتاً")
            null
        }
    }

    private fun round2(v: Float): Double = Math.round(v * 100f) / 100.0

    private fun parse(j: JSONObject, c: BrainContext): Decision {
        val say = j.optString("say", "").take(40)
        val secs = j.optDouble("seconds", 3.0).toFloat().coerceIn(1.5f, 10f)
        val nx = j.optDouble("x", 0.5).toFloat().coerceIn(0.05f, 0.95f)
        val ny = j.optDouble("y", 0.5).toFloat().coerceIn(0.05f, 0.95f)
        val idx = j.optInt("icon", -1)
        val icon = c.icons.take(30).getOrNull(idx)
        return when (j.optString("action", "wander")) {
            "inspect_icon" -> if (icon != null) Decision(Act.INSPECT, icon, say = say, seconds = secs)
                              else Decision(Act.WANDER, nx = nx, ny = ny, say = say)
            "open_icon" -> if (icon != null && c.openApps) Decision(Act.OPEN, icon, say = say, seconds = secs)
                           else if (icon != null) Decision(Act.INSPECT, icon, say = say, seconds = secs)
                           else Decision(Act.WANDER, nx = nx, ny = ny, say = say)
            "build_web" -> if (c.webEnabled && c.webProgress < 1f) Decision(Act.WEB, say = say)
                           else Decision(Act.REST, say = say, seconds = secs)
            "rest" -> Decision(Act.REST, say = say, seconds = secs)
            else -> Decision(Act.WANDER, nx = nx, ny = ny, say = say)
        }
    }
}
