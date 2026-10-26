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
        fun enqueue(c: Context, base: String, redo: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf("base" to base, "redo" to redo))
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
        val truncated: Boolean,
        val note: String = "",
        val repeated: Boolean = false
    ) {
        val chars: Int get() = lines.sumOf { it.text.length }
        val suspect: Boolean get() = gaps.isNotEmpty() || lowRate || truncated || repeated
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
            Store.writeState(c, base, "failed", "설정에서 Gemini API 키를 넣은 뒤 다시 전사를 눌러 주세요")
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

        // 누락 의심 구간만 다시 전사
        if (inputData.getBoolean("redo", false)) {
            for (ch in chunks) {
                val pf = File(parts, "chunk_%02d.json".format(ch.index))
                val o = JSONObject(pf.readText())
                if (o.optBoolean("redone")) continue
                val old = fromJson(o)
                if (!old.suspect) continue
                progress = "${ch.index + 1}/${chunks.size} 조각 다시 전사"
                Store.writeState(c, base, "transcribing", progress)
                try {
                    val ev = redoChunk(audio, ch, groqKey, key, models, speech, parts, old)
                    pf.writeText(toJson(ev).put("redone", true).toString())
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
        }

        val (text, suspectCount) = assemble(base, dur, chunks, parts)
        Store.txt(c, base).writeText(text)
        val msg = if (suspectCount > 0) "누락 의심 ${suspectCount}곳" else ""
        Store.writeState(c, base, "done", msg)
        Notif.done(c, "전사 완료", if (msg.isEmpty()) base else "$base · $msg")
        return Result.success()
    }

    /**
     * 한 조각: Groq Whisper로 먼저 전사. Groq가 막혔거나 누락 의심이면 Gemini로 시도.
     * 누락 의심이 없으면 바로 채택, 계속 의심이면 가장 긴 결과를 채택.
     */
    private fun describe(ev: Eval): String {
        val p = mutableListOf<String>()
        ev.gaps.forEach { p.add(fmt(it.first) + "–" + fmt(it.second) + " 공백") }
        if (ev.lowRate) p.add("분량 부족")
        if (ev.truncated) p.add("잘림")
        if (ev.repeated) p.add("같은 문장 반복")
        return p.joinToString(", ")
    }

    /**
     * 한 조각: Gemini로 먼저 전사(모델 순서대로, 의심이면 다른 모델로 최대 3번).
     * Gemini가 모두 막혔거나 계속 의심이면 Groq Whisper를 예비로 써 보고, 가장 나은 결과를 고름.
     */
    private suspend fun transcribeChunk(
        audio: File, ch: Chunk, groqKey: String, gemKey: String, models: List<String>, speech: Speech, parts: File
    ): Eval {
        val tmp = File(parts, "chunk_%02d.m4a".format(ch.index))
        val actualStart = withContext(Dispatchers.IO) { Splitter.extract(audio, ch.sendStart, ch.sendEnd, tmp) }
        val bytes = tmp.readBytes()
        val found = mutableListOf<Eval>()
        var busy: AllBusy? = null
        var note = ""
        try {
            if (gemKey.isNotEmpty() && models.isNotEmpty()) {
                val prompt = Gemini.prompt((ch.sendEnd - ch.sendStart) / 60000.0)
                for (attempt in 0 until 3) {
                    val k = attempt % models.size
                    val order = models.drop(k) + models.take(k)
                    try {
                        val pair = call(gemKey, order, bytes, prompt)
                        val lines = parse(pair.second.text, actualStart)
                            .filter { it.t >= ch.sendStart - 2000 && it.t < ch.sendEnd + 2000 }
                        val ev = evaluate(lines, ch, speech, pair.second.truncated, pair.first)
                        if (!ev.suspect) return ev
                        found.add(ev)
                        note = "Gemini 결과 누락 의심(${describe(ev)})"
                    } catch (e: AllBusy) {
                        busy = e
                        note = "Gemini 막힘(${e.message?.take(60)})"
                        break
                    }
                }
            } else {
                note = "Gemini 키 없음"
            }
            if (groqKey.isNotEmpty()) {
                status("$note → Groq 예비 사용")
                try {
                    val segs = callGroq(groqKey, bytes)
                    val lines = segs.map { Line(actualStart + (it.start * 1000).toLong(), it.text) }
                        .filter { it.t >= ch.sendStart - 2000 && it.t < ch.sendEnd + 2000 }
                    val ev = evaluate(lines, ch, speech, false, Groq.MODEL)
                    if (!ev.suspect && found.isEmpty()) return ev.copy(note = "$note → Groq")
                    found.add(ev)
                } catch (e: AllBusy) {
                    busy = e
                }
            }
        } finally {
            tmp.delete()
        }
        if (found.isEmpty()) throw (busy ?: AllBusy("사용할 수 있는 엔진이 없습니다"))
        val pick = found.sortedWith(compareBy<Eval>({ it.suspect }, { -it.chars })).first()
        return pick.copy(note = "$note · 후보 ${found.size}개 중 ${pick.model} 결과 사용")
    }

    /** Groq(예비): 바쁘면 대기 후 3번까지 재시도, 그래도 안 되면 AllBusy */
    private suspend fun callGroq(key: String, bytes: ByteArray): List<Groq.Seg> {
        val waits = longArrayOf(5_000, 20_000, 60_000)
        var lastErr = ""
        var a = 0
        while (true) {
            try {
                status("Groq Whisper 요청 중")
                return withContext(Dispatchers.IO) { Groq.transcribe(key, bytes, "") }
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

    private fun evaluate(all: List<Line>, ch: Chunk, speech: Speech, truncated: Boolean, model: String): Eval {
        val lines = all.filter { it.t >= ch.coreStart - 2000 && it.t < ch.coreEnd }
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
        // 같은 문장이 3번 이상 나오면 받아 적지 못한 구간을 헛말로 채운 것으로 봄
        val rep = lines.map { l -> l.text.lowercase().filter { it.isLetterOrDigit() } }
            .filter { it.length >= 6 }
            .groupingBy { it }.eachCount().values.any { it >= 3 }
        return Eval(all, model, gaps, low, truncated, repeated = rep)
    }

    private fun fromJson(o: JSONObject): Eval {
        val ls = o.getJSONArray("lines")
        val lines = (0 until ls.length()).map { val a = ls.getJSONArray(it); Line(a.getLong(0), a.getString(1)) }
        val gs = o.getJSONArray("gaps")
        val gaps = (0 until gs.length()).map { val g = gs.getJSONArray(it); g.getLong(0) to g.getLong(1) }
        return Eval(lines, o.optString("model"), gaps, o.optBoolean("low"), o.optBoolean("truncated"), o.optString("note"), o.optBoolean("rep"))
    }

    /** 의심 조각: Groq와 Gemini를 모두 돌려 보고, 기존 결과까지 포함해 가장 나은 것을 고름 */
    private suspend fun redoChunk(
        audio: File, ch: Chunk, groqKey: String, gemKey: String, models: List<String>,
        speech: Speech, parts: File, old: Eval
    ): Eval {
        val tmp = File(parts, "chunk_%02d.m4a".format(ch.index))
        val actualStart = withContext(Dispatchers.IO) { Splitter.extract(audio, ch.sendStart, ch.sendEnd, tmp) }
        val bytes = tmp.readBytes()
        val found = mutableListOf<Eval>()
        var lastBusy: AllBusy? = null
        try {
            if (groqKey.isNotEmpty()) {
                try {
                    val segs = callGroq(groqKey, bytes)
                    val lines = segs.map { Line(actualStart + (it.start * 1000).toLong(), it.text) }
                        .filter { it.t >= ch.sendStart - 2000 && it.t < ch.sendEnd + 2000 }
                    found.add(evaluate(lines, ch, speech, false, Groq.MODEL))
                } catch (e: AllBusy) {
                    lastBusy = e
                }
            }
            if (gemKey.isNotEmpty() && models.isNotEmpty()) {
                val prompt = Gemini.prompt((ch.sendEnd - ch.sendStart) / 60000.0)
                for (attempt in 0 until minOf(2, models.size)) {
                    val order = models.drop(attempt) + models.take(attempt)
                    try {
                        val pair = call(gemKey, order, bytes, prompt)
                        val lines = parse(pair.second.text, actualStart)
                            .filter { it.t >= ch.sendStart - 2000 && it.t < ch.sendEnd + 2000 }
                        val ev = evaluate(lines, ch, speech, pair.second.truncated, pair.first)
                        found.add(ev)
                        if (!ev.suspect) break
                    } catch (e: AllBusy) {
                        lastBusy = e
                        break
                    }
                }
            }
        } finally {
            tmp.delete()
        }
        if (found.isEmpty()) throw (lastBusy ?: AllBusy("사용할 수 있는 엔진이 없습니다"))
        val coreChars = { e: Eval -> e.lines.filter { it.t >= ch.coreStart && it.t < ch.coreEnd }.sumOf { it.text.length } }
        val pick = (found + old).sortedWith(compareBy<Eval>({ it.suspect }, { -coreChars(it) })).first()
        val what = if (pick === old) "기존 결과 유지" else "${pick.model} 결과로 교체"
        val tail = if (pick.suspect) " · 여전히 의심(${describe(pick)})" else ""
        return pick.copy(note = "다시 전사: 후보 ${found.size + 1}개 중 $what$tail")
    }

    private fun toJson(ev: Eval): JSONObject {
        val ls = JSONArray()
        ev.lines.forEach { ls.put(JSONArray().put(it.t).put(it.text)) }
        val gs = JSONArray()
        ev.gaps.forEach { gs.put(JSONArray().put(it.first).put(it.second)) }
        return JSONObject()
            .put("model", ev.model)
            .put("note", ev.note)
            .put("rep", ev.repeated)
            .put("low", ev.lowRate)
            .put("truncated", ev.truncated)
            .put("gaps", gs)
            .put("lines", ls)
    }

    private fun assemble(base: String, dur: Long, chunks: List<Chunk>, parts: File): Pair<String, Int> {
        val body = mutableListOf<Line>()
        val markers = mutableListOf<Line>()
        val modelCount = linkedMapOf<String, Int>()
        val notes = mutableListOf<String>()
        var suspects = 0
        var resume = 0L
        for (ch in chunks) {
            val o = JSONObject(File(parts, "chunk_%02d.json".format(ch.index)).readText())
            val m = o.optString("model")
            modelCount[m] = (modelCount[m] ?: 0) + 1
            val note = o.optString("note")
            if (note.isNotEmpty()) {
                notes.add("# 조각 ${ch.index + 1} (${fmt(ch.coreStart)}–${fmt(ch.coreEnd)}): $m · $note")
            }

            val ls = o.getJSONArray("lines")
            val lines = (0 until ls.length()).map { val a = ls.getJSONArray(it); Line(a.getLong(0), a.getString(1)) }
                .sortedBy { it.t }
            // 조각 경계 처리: 다음 조각이 이미 들은 구간(다음 조각 시작+1초 이후)의 줄은 다음 조각 것으로 씀.
            // 이렇게 하면 경계를 걸친 문장이 두 조각에 중복으로 들어가지 않음.
            val next = chunks.getOrNull(ch.index + 1)
            val handOff = next?.let { it.sendStart + 1000 } ?: Long.MAX_VALUE
            val from = resume - 1500
            var firstDropped = -1L
            for (ln in lines) {
                if (ln.t < from || ln.t >= ch.coreEnd) continue
                if (ln.t >= handOff) {
                    if (firstDropped < 0) firstDropped = ln.t
                    continue
                }
                body.add(ln)
            }
            resume = if (firstDropped >= 0) firstDropped else ch.coreEnd

            val gs = o.getJSONArray("gaps")
            for (i in 0 until gs.length()) {
                val g = gs.getJSONArray(i)
                markers.add(Line(g.getLong(0), fmt(g.getLong(0)) + "–" + fmt(g.getLong(1)) + " 공백"))
                suspects++
            }
            if (o.optBoolean("rep")) {
                markers.add(Line(ch.coreStart, fmt(ch.coreStart) + "–" + fmt(ch.coreEnd) + " 같은 문장 반복"))
                suspects++
            }
            if (o.optBoolean("low") || o.optBoolean("truncated")) {
                markers.add(Line(ch.coreStart, fmt(ch.coreStart) + "–" + fmt(ch.coreEnd) + " 분량 부족"))
                suspects++
            }
        }
        val merged = body.map { Line(it.t, "[" + fmt(it.t) + "] " + it.text) }.sortedBy { it.t }
        val modelsStr = modelCount.entries.joinToString(", ") { "${it.key} ${it.value}조각" }
        val sb = StringBuilder()
        sb.append("# ").append(base).append('\n')
        sb.append("# 녹음 길이 ").append(fmt(dur)).append(" · 전사 모델: ").append(modelsStr).append('\n')
        notes.forEach { sb.append(it).append('\n') }
        if (suspects > 0) {
            sb.append("# 누락 의심 ").append(suspects).append("곳: ")
                .append(markers.sortedBy { it.t }.joinToString(", ") { it.text }).append('\n')
        }
        sb.append('\n')
        merged.forEach { sb.append(it.text).append('\n') }
        return sb.toString() to suspects
    }
}
