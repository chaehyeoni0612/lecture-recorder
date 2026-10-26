package com.lecturerec.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, msg: String) : Exception(msg)

data class GeminiResult(val text: String, val truncated: Boolean)

object Gemini {
    fun prompt(minutes: Double): String = """
이 오디오는 의과대학 강의 녹음의 한 부분(약 ${"%.0f".format(minutes)}분)입니다.
들리는 말을 처음부터 끝까지 빠짐없이 그대로 한국어로 받아 적으세요.

규칙:
- 요약, 생략, 정리를 하지 마세요. 말한 문장을 그대로 적습니다. 끝부분까지 같은 밀도로 적으세요.
- 영어로 말한 의학 용어는 영어 원어로 적습니다.
- 모든 줄은 [mm:ss] 로 시작합니다. 이 오디오의 시작을 [00:00]으로 합니다.
- 10~20초마다, 또는 문장이 끝날 때 새 줄을 시작합니다. 한 줄이 20초를 넘지 않게 하세요.
- 알아들을 수 없는 부분은 [불명확]으로 표시합니다.
- 말이 없는 구간은 적지 않습니다.
- 전사문 외의 설명, 제목, 코드블록은 쓰지 마세요.
""".trim()

    fun transcribe(key: String, model: String, audio: ByteArray, prompt: String): GeminiResult {
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data", JSONObject()
                                        .put("mime_type", "audio/mp4")
                                        .put("data", Base64.encodeToString(audio, Base64.NO_WRAP))
                                )
                            )
                            .put(JSONObject().put("text", prompt))
                    )
                )
            )
            .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 32768))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 30_000
            con.readTimeout = 600_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", key)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = con.responseCode
            val resp = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(resp).getJSONObject("error").optString("message")
                } catch (e: Exception) {
                    resp.take(200)
                }
                throw ApiException(code, msg)
            }

            val o = JSONObject(resp)
            val cands = o.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) throw ApiException(-1, "빈 응답")
            val c0 = cands.getJSONObject(0)
            val parts = c0.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.getJSONObject(i)
                    if (p.optBoolean("thought", false)) continue
                    sb.append(p.optString("text"))
                }
            }
            val finish = c0.optString("finishReason")
            // 말이 없는 짧은 녹음이면 빈 결과가 정상. 그 외 이유로 비었을 때만 오류로 봄.
            if (sb.isBlank() && finish != "STOP" && finish != "") throw ApiException(-1, "빈 응답 ($finish)")
            return GeminiResult(sb.toString(), c0.optString("finishReason") == "MAX_TOKENS")
        } finally {
            con.disconnect()
        }
    }
}
