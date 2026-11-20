package com.lecturerec.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 녹음 하나 = base 이름 하나. 같은 폴더에 .m4a / .amp / .txt / .state.json / _parts/ */
object Store {
    data class State(val status: String, val msg: String = "", val step: Int = -1)

    /**
     * 내부 저장소 사용 (제목의 ':'은 외부 저장소에서 파일 이름으로 못 써서).
     * 예전 버전이 외부 저장소에 둔 파일은 처음 한 번 옮겨 옴.
     */
    @Volatile private var migrated = false

    fun dir(c: Context): File {
        val d = File(c.filesDir, "recordings").apply { mkdirs() }
        if (!migrated) {
            synchronized(this) {
                if (!migrated) {
                    val old = c.getExternalFilesDir(null)?.let { File(it, "recordings") }
                    if (old != null && old.isDirectory) {
                        old.listFiles()?.forEach { f ->
                            val t = File(d, f.name)
                            if (!t.exists()) {
                                try {
                                    if (f.isDirectory) f.copyRecursively(t) else f.copyTo(t)
                                    t.setLastModified(f.lastModified())
                                } catch (e: Exception) { }
                            }
                        }
                        old.deleteRecursively()
                    }
                    migrated = true
                }
            }
        }
        return d
    }

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
            .sortedByDescending { sortKey(it) }

    /** 날짜+시각으로 정렬 (새 형식 (HH:MM), 옛 형식 HH시MM분 둘 다) */
    fun sortKey(base: String): String {
        val d = Regex("""_(\d{6})_""").find(base)?.groupValues?.get(1) ?: "000000"
        val t = Regex("""\((\d{2}):(\d{2})\)""").find(base)?.let { it.groupValues[1] + it.groupValues[2] }
            ?: Regex("""(\d{2})시(\d{2})분""").find(base)?.let { it.groupValues[1] + it.groupValues[2] }
            ?: "0000"
        return d + t + base
    }

    /** 시작 시각으로 교시 정하기 (8시대 1교시 … 11시대 4교시, 13시대 5교시 … 16시대 8교시). 그 외 0 */
    fun periodOf(t: LocalDateTime): Int = when (t.hour) {
        in 8..11 -> t.hour - 7
        in 13..16 -> t.hour - 8
        else -> 0
    }

    private fun clean(s: String) = s.replace(Regex("""[\\/*?"<>|]"""), "").trim()

    /** 과목_YYMMDD_N교시(HH:MM)_녹음자  (교시 0이면 (HH:MM)만, 겹치면 _2, _3 …) */
    fun newBase(c: Context, subject: String, t: LocalDateTime, period: Int? = null, recorder: String? = null): String {
        val subj = clean(subject).ifEmpty { "강의" }
        val p = period ?: periodOf(t)
        val hm = t.format(DateTimeFormatter.ofPattern("HH:mm"))
        val who = clean(recorder ?: Prefs.recorderName(c))
        val name = subj + "_" + t.format(DateTimeFormatter.ofPattern("yyMMdd")) + "_" +
            (if (p > 0) "${p}교시($hm)" else "($hm)") + (if (who.isNotEmpty()) "_$who" else "")
        var base = name
        var n = 2
        while (exists(c, base)) {
            base = "${name}_$n"
            n++
        }
        return base
    }

    fun exists(c: Context, base: String) =
        audio(c, base).exists() || src(c, base).exists() || stateFile(c, base).exists()

    /** 이 기기의 녹음 파일 묶음 이름 바꾸기 */
    fun rename(c: Context, from: String, to: String): Boolean {
        if (from == to || exists(c, to)) return false
        val pairs = listOf(".m4a", ".src", ".amp", ".txt", ".state.json").map {
            File(dir(c), from + it) to File(dir(c), to + it)
        } + listOf(File(dir(c), "${from}_parts") to File(dir(c), "${to}_parts"))
        for ((a, b) in pairs) if (a.exists()) a.renameTo(b)
        return true
    }

    fun readState(c: Context, base: String): State {
        val f = stateFile(c, base)
        if (!f.exists()) return State("recorded")
        return try {
            val o = JSONObject(f.readText())
            State(o.optString("status", "recorded"), o.optString("msg", ""), o.optInt("step", -1))
        } catch (e: Exception) {
            State("recorded")
        }
    }

    /** step: 지금 단계 (0 변환, 1 전사, 2 누락 확인, 3 정리), -1이면 표시 안 함 */
    fun writeState(c: Context, base: String, status: String, msg: String = "", step: Int = -1) {
        stateFile(c, base).writeText(JSONObject().put("status", status).put("msg", msg).put("step", step).toString())
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
