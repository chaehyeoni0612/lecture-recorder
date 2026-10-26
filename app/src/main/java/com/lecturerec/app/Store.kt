package com.lecturerec.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 녹음 하나 = base 이름 하나. 같은 폴더에 .m4a / .amp / .txt / .state.json / _parts/ */
object Store {
    data class State(val status: String, val msg: String = "")

    fun dir(c: Context): File =
        File(c.getExternalFilesDir(null) ?: c.filesDir, "recordings").apply { mkdirs() }

    fun audio(c: Context, base: String) = File(dir(c), "$base.m4a")
    /** 불러온 원본(변환 전). 전사 작업이 m4a로 변환한 뒤 지움. */
    fun src(c: Context, base: String) = File(dir(c), "$base.src")
    fun amp(c: Context, base: String) = File(dir(c), "$base.amp")
    fun txt(c: Context, base: String) = File(dir(c), "$base.txt")
    private fun stateFile(c: Context, base: String) = File(dir(c), "$base.state.json")
    fun partsDir(c: Context, base: String) = File(dir(c), "${base}_parts").apply { mkdirs() }

    fun list(c: Context): List<String> =
        (dir(c).listFiles { f ->
            f.name.endsWith(".m4a") || f.name.endsWith(".src") || f.name.endsWith(".state.json")
        } ?: emptyArray())
            .map { it.name.removeSuffix(".state.json").removeSuffix(".m4a").removeSuffix(".src") }
            .distinct()
            .sortedByDescending { it.substringAfter('_', "") }

    /** 과목_YYMMDD_HH시MM분 (겹치면 _2, _3 …) */
    fun newBase(c: Context, subject: String, t: LocalDateTime): String {
        val clean = subject.replace(Regex("""[\\/:*?"<>|]"""), "").trim().ifEmpty { "강의" }
        val name = clean + "_" + t.format(DateTimeFormatter.ofPattern("yyMMdd")) +
            "_" + t.format(DateTimeFormatter.ofPattern("HH")) + "시" +
            t.format(DateTimeFormatter.ofPattern("mm")) + "분"
        var base = name
        var n = 2
        while (audio(c, base).exists() || src(c, base).exists()) {
            base = "${name}_$n"
            n++
        }
        return base
    }

    fun readState(c: Context, base: String): State {
        val f = stateFile(c, base)
        if (!f.exists()) return State("recorded")
        return try {
            val o = JSONObject(f.readText())
            State(o.optString("status", "recorded"), o.optString("msg", ""))
        } catch (e: Exception) {
            State("recorded")
        }
    }

    fun writeState(c: Context, base: String, status: String, msg: String = "") {
        stateFile(c, base).writeText(JSONObject().put("status", status).put("msg", msg).toString())
    }

    fun clearTranscript(c: Context, base: String) {
        txt(c, base).delete()
        File(dir(c), "${base}_parts").deleteRecursively()
    }

    fun delete(c: Context, base: String) {
        clearTranscript(c, base)
        audio(c, base).delete()
        src(c, base).delete()
        amp(c, base).delete()
        stateFile(c, base).delete()
    }
}
