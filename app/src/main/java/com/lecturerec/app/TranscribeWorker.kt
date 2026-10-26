package com.lecturerec.app

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AllBusy(msg: String) : Exception(msg)
class FatalApi(msg: String) : Exception(msg)

data class Line(val t: Long, val text: String)

class TranscribeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        fun enqueue(c: Context, base: String) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf("base" to base))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork("tr_$base", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(c: Context, base: String) {
            WorkManager.getInstance(c).cancelUniqueWork("tr_$base")
        }

        private val TS = Regex("""^\s*\[(\d{1,3}):(\d{2})(?::(\d{2}))?]\s*(.*)$""")

        fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d".format(s / 60, s % 60)
        }
    }

    private data class Eval(
        val lines: List<Line>,
        val model: String,
        val gaps: List<Pair<Long, Long>>,
        val lowRate: Boolean,
        val truncated: Boolean
    ) {
        val chars: Int get() = lines.sumOf { it.text.length }
        val suspect: Boolean get() = gaps.isNotEmpty() || lowRate || truncated
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notif.ensureChannels(applicationContext)
        val n = Notif.working(applicationContext, inputData.getString("base") ?: "")
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notif.WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notif.WORK_ID, n)
        }
    }

    private var curBase = ""
    private var progress = ""

    private fun status(msg: String) {
        Store.writeState(applicationContext, curBase, "transcribing", "$progress · $msg")
    }

    override suspend fun doWork(): Result {
        val c = applicationContext
        val base = inputData.getString("base") ?: return Result.failure()
        curBase = base
        val audio = Store.audio(c, base)
        val src = Store.src(c, base)
        if (!audio.exists() && src.exists()) {
            try { setForeground(getForegroundInfo()) } catch (e: Exception) { }
            Store.writeState(c, base, "transcribing", "파일 변환 중")
            try {
                withContext(Dispatchers.IO) { Importer.transcode(src, audio, Store.amp(c, base)) }
                src.delete()
            } catch (e: Exception) {
                audio.delete()
                Store.amp(c, base).delete()
                Store.writeState(c, base, "failed", "변환 실패 · ${e.message}")
                return Result.failure()
            }
        }
        if (!audio.exists()) return Result.failure()

        val groqKey = Prefs.groqKey(c)
        val key = Prefs.apiKey(c)
        val models = Prefs.models(c)
        if (groqKey.isEmpty() && (key.isEmpty() || models.isEmpty())) {
            Store.writeState(c, base, "failed", "설정에서 Groq API 키를 넣은 뒤 다시 전사를 눌러 주세요")
            return Result.failure()
        }

        try { setForeground(getForegroundInfo()) } catch (e: Exception) { }

        val amps = Splitter.readAmps(Store.amp(c, base))
        val dur = Splitter.durationMs(audio)
        if (dur <= 0) {
            Store.writeState(c, base, "failed", "녹음 파일을 읽을 수 없습니다")
            return Result.failure()
        }
        val chunks = Splitter.plan(dur, amps)
        val speech = Speech(amps)
        val parts = Store.partsDir(c, base)

        for (ch in chunks) {
            val pf = File(parts, "chunk_%02d.json".format(ch.index))
            if (pf.exists()) continue
            progress = "${ch.index + 1}/${chunks.size} 조각"
            Store.writeState(c, base, "transcribing", progress)
            try {
                val ev = transcribeChunk(audio, ch, groqKey, key, models, speech, parts)
                pf.writeText(toJson(ev).toString())
            } catch (e: AllBusy) {
                Store.writeState(c, base, "waiting", "엔진이 모두 바쁨, 자동 재시도 예정 · ${e.message}")
                return Result.retry()
            } catch (e: FatalApi) {
                Store.writeState(c, base, "failed", e.message ?: "API 오류")
                return Result.failure()
            } catch (e: Exception) {
                Store.writeState(c, base, "waiting", "오류, 자동 재시도 예정 · ${e.message}")
                return Result.retry()
            }
        }

        val (text, suspectCount) = assemble(base, dur, chunks, parts)
        Store.txt(c, base).writeText(text)
        val msg = if (suspectCount > 0) "누락 의심 ${suspectCount}곳 표시됨" else ""
        Store.writeState(c, base, "done", msg)
        Notif.done(c, "전사 완료", if (msg.isEmpty()) base else "$base · $msg")
        return Result.success()
    }

    /**
     * 한 조각: Groq Whisper로 먼저 전사. Groq가 막혔거나 누락 의심이면 Gemini로 시도.
     * 누락 의심이 없으면 바로 채택, 계속 의심이면 가장 긴 결과를 채택.
     */
    private suspend fun transcribeChunk(
        audio: File, ch: Chunk, groqKey: String, gemKey: String, models: List<String>, speech: Speech, parts: File
    ): Eval {
        val tmp = File(parts, "chunk_%02d.m4a".format(ch.index))
        val actualStart = withContext(Dispatchers.IO) { Splitter.extract(audio, ch.sendStart, ch.sendEnd, tmp) }
        val bytes = tmp.readBytes()
        var best: Eval? = null
        var busy: AllBusy? = null
        try {
            if (groqKey.isNotEmpty()) {
                try {
                    val segs = callGroq(groqKey, bytes)
                    val lines = segs.map { Line(actualStart + (it.start * 1000).toLong(), it.text) }
                        .filter { it.t >= ch.coreStart - 2000 && it.t < ch.coreEnd }
                    val ev = evaluate(lines, ch, speech, false, Groq.MODEL)
                    if (!ev.suspect) return ev
                    best = ev
                } catch (e: AllBusy) {
                    busy = e
                }
            }
            if (gemKey.isNotEmpty() && models.isNotEmpty()) {
                val prompt = Gemini.prompt((ch.sendEnd - ch.sendStart) / 60000.0)
                val tries = if (best == null) 3 else 1
                for (attempt in 0 until tries) {
                    val k = attempt % models.size
                    val order = models.drop(k) + models.take(k)
                    val pair = try {
                        call(gemKey, order, bytes, prompt)
                    } catch (e: AllBusy) {
                        if (best != null) return best
                        throw e
                    }
                    val lines = parse(pair.second.text, actualStart).filter { it.t >= ch.coreStart - 2000 && it.t < ch.coreEnd }
                    val ev = evaluate(lines, ch, speech, pair.second.truncated, pair.first)
                    if (!ev.suspect) return ev
                    val cur = best
                    if (cur == null || ev.chars > cur.chars) best = ev
                }
            }
        } finally {
            tmp.delete()
        }
        return best ?: throw (busy ?: AllBusy("사용할 수 있는 엔진이 없습니다"))
    }

    /** Groq: 바쁘면 대기 후 3번까지 재시도, 그래도 안 되면 AllBusy (→ Gemini로 넘어감) */
    private suspend fun callGroq(key: String, bytes: ByteArray): List<Groq.Seg> {
        val waits = longArrayOf(5_000, 20_000, 60_000)
        var lastErr = ""
        var a = 0
        while (true) {
            try {
                status("Groq Whisper 요청 중")
                return withContext(Dispatchers.IO) { Groq.transcribe(key, bytes, "의과대학 강의입니다. 의학 용어는 영어로 적습니다.") }
            } catch (e: ApiException) {
                lastErr = "Groq ${e.code}: ${e.message}"
                if (e.code == 400 || e.code == 401 || e.code == 403) throw FatalApi("Groq API 오류 ${e.code}: ${e.message}")
            } catch (e: IOException) {
                lastErr = "Groq 네트워크: ${e.message}"
            }
            if (a >= waits.size) throw AllBusy(lastErr.take(120))
            status("${lastErr.take(60)} · ${waits[a] / 1000}초 후 재시도")
            delay(waits[a])
            a++
        }
    }

    /** 허용된 모델만 순서대로. 모델마다 대기 후 재시도 3번. 전부 막히면 AllBusy. */
    private suspend fun call(key: String, models: List<String>, bytes: ByteArray, prompt: String): Pair<String, GeminiResult> {
        val waits = longArrayOf(5_000, 20_000, 60_000)
        var lastErr = ""
        var allNotFound = true
        for (m in models) {
            var a = 0
            while (true) {
                try {
                    status("$m 요청 중")
                    val r = withContext(Dispatchers.IO) { Gemini.transcribe(key, m, bytes, prompt) }
                    return m to r
                } catch (e: ApiException) {
                    lastErr = "$m ${e.code}: ${e.message}"
                    when (e.code) {
                        404 -> break
                        400, 401, 403 -> throw FatalApi("API 오류 ${e.code}: ${e.message}")
                    }
                    allNotFound = false
                } catch (e: IOException) {
                    lastErr = "$m 네트워크: ${e.message}"
                    allNotFound = false
                }
                if (a >= waits.size) break
                status("${lastErr.take(60)} · ${waits[a] / 1000}초 후 재시도")
                delay(waits[a])
                a++
            }
        }
        if (allNotFound) throw FatalApi("설정의 모델 이름을 찾을 수 없습니다 · $lastErr")
        throw AllBusy(lastErr.take(120))
    }

    private fun parse(text: String, offset: Long): List<Line> {
        val out = mutableListOf<Line>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            val m = TS.find(line)
            if (m != null) {
                val g = m.groupValues
                val secs = if (g[3].isNotEmpty()) {
                    g[1].toLong() * 3600 + g[2].toLong() * 60 + g[3].toLong()
                } else {
                    g[1].toLong() * 60 + g[2].toLong()
                }
                if (g[4].isNotBlank()) out.add(Line(offset + secs * 1000, g[4].trim()))
            } else if (out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out.add(Line(last.t, last.text + " " + line))
            } else {
                out.add(Line(offset, line))
            }
        }
        return out
    }

    private fun evaluate(lines: List<Line>, ch: Chunk, speech: Speech, truncated: Boolean, model: String): Eval {
        val gaps = mutableListOf<Pair<Long, Long>>()
        val ts = listOf(ch.coreStart) + lines.map { it.t }.sorted() + listOf(ch.coreEnd)
        for (i in 0 until ts.size - 1) {
            val a = ts[i]
            val b = ts[i + 1]
            if (b - a > 90_000 && speech.speakingMs(a, b) > (b - a) * 0.3) gaps.add(a to b)
        }
        val speakMin = speech.speakingMs(ch.coreStart, ch.coreEnd) / 60000.0
        val chars = lines.sumOf { it.text.length }
        val low = speakMin > 1.0 && chars < speakMin * 120
        return Eval(lines, model, gaps, low, truncated)
    }

    private fun toJson(ev: Eval): JSONObject {
        val ls = JSONArray()
        ev.lines.forEach { ls.put(JSONArray().put(it.t).put(it.text)) }
        val gs = JSONArray()
        ev.gaps.forEach { gs.put(JSONArray().put(it.first).put(it.second)) }
        return JSONObject()
            .put("model", ev.model)
            .put("low", ev.lowRate)
            .put("truncated", ev.truncated)
            .put("gaps", gs)
            .put("lines", ls)
    }

    private fun assemble(base: String, dur: Long, chunks: List<Chunk>, parts: File): Pair<String, Int> {
        val body = mutableListOf<Line>()
        val modelCount = linkedMapOf<String, Int>()
        var suspects = 0
        for (ch in chunks) {
            val o = JSONObject(File(parts, "chunk_%02d.json".format(ch.index)).readText())
            val m = o.optString("model")
            modelCount[m] = (modelCount[m] ?: 0) + 1
            val ls = o.getJSONArray("lines")
            for (i in 0 until ls.length()) {
                val a = ls.getJSONArray(i)
                body.add(Line(a.getLong(0), "[" + fmt(a.getLong(0)) + "] " + a.getString(1)))
            }
            val gs = o.getJSONArray("gaps")
            for (i in 0 until gs.length()) {
                val g = gs.getJSONArray(i)
                body.add(Line(g.getLong(0) + 1, "[누락 의심 " + fmt(g.getLong(0)) + "–" + fmt(g.getLong(1)) + "]"))
                suspects++
            }
            if (o.optBoolean("low") || o.optBoolean("truncated")) {
                body.add(
                    Line(ch.coreStart, "[누락 의심 " + fmt(ch.coreStart) + "–" + fmt(ch.coreEnd) + " · 분량 부족]")
                )
                suspects++
            }
        }
        body.sortBy { it.t }
        val modelsStr = modelCount.entries.joinToString(", ") { "${it.key} ${it.value}조각" }
        val sb = StringBuilder()
        sb.append("# ").append(base).append('\n')
        sb.append("# 녹음 길이 ").append(fmt(dur)).append(" · 전사 모델: ").append(modelsStr).append('\n')
        if (suspects > 0) sb.append("# 누락 의심 ").append(suspects).append("곳 (본문에 [누락 의심] 표시)\n")
        sb.append('\n')
        body.forEach { sb.append(it.text).append('\n') }
        return sb.toString() to suspects
    }
}
