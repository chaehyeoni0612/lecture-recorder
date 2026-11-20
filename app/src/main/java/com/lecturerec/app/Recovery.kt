package com.lecturerec.app

import android.content.Context

/** "녹음 중"으로 멈춰 있지만 실제로는 녹음하고 있지 않은 항목 정리 */
object Recovery {
    fun run(c: Context) {
        if (RecordService.currentBase != null) return
        RecordService.clearSaved(c)
        for (base in Store.list(c)) {
            if (Store.readState(c, base).status != "recording") continue
            val src = Store.src(c, base)
            val audio = Store.audio(c, base)
            if ((src.exists() && src.length() > 4096) || (audio.exists() && audio.length() > 4096)) {
                Store.writeState(c, base, "queued", "녹음이 중간에 끊김, 남은 만큼 전사")
                TranscribeWorker.enqueue(c, base)
            } else {
                Store.writeState(c, base, "failed", "녹음 파일이 없습니다")
            }
        }
    }
}
