package com.lecturerec.app

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Groq Whisper 전사. 문장 단위 시작 시각(초)과 글을 돌려줌. */
object Groq {
    const val MODEL = "whisper-large-v3"

    data class Seg(val start: Double, val text: String)

    fun transcribe(key: String, audio: ByteArray, prompt: String): List<Seg> {
        val boundary = "----lecrec" + System.currentTimeMillis()
        val con = URL("https://api.groq.com/openai/v1/audio/transcriptions").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 30_000
            con.readTimeout = 300_000
            con.doOutput = true
            con.setRequestProperty("Authorization", "Bearer $key")
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            DataOutputStream(con.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("model", MODEL)
                field("language", "ko")
                field("response_format", "verbose_json")
                field("temperature", "0")
                if (prompt.isNotBlank()) field("prompt", prompt)
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.m4a\"\r\n" +
                        "Content-Type: audio/mp4\r\n\r\n").toByteArray()
                )
                out.write(audio)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

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

            val segs = JSONObject(resp).optJSONArray("segments") ?: return emptyList()
            val out = mutableListOf<Seg>()
            var prev = ""
            var repeat = 0
            for (i in 0 until segs.length()) {
                val s = segs.getJSONObject(i)
                val text = s.optString("text").trim()
                if (text.isEmpty()) continue
                // 무음 구간에서 지어낸 문장 거르기
                if (s.optDouble("no_speech_prob", 0.0) > 0.6 && s.optDouble("avg_logprob", 0.0) < -1.0) continue
                // 같은 문장이 연달아 반복되면 헛말로 보고 2번째부터 버림
                if (text == prev) {
                    repeat++
                    if (repeat >= 1) continue
                } else {
                    repeat = 0
                }
                prev = text
                out.add(Seg(s.optDouble("start", 0.0), text))
            }
            return out
        } finally {
            con.disconnect()
        }
    }
}
