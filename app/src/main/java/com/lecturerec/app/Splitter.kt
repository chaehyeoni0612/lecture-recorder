package com.lecturerec.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** coreStart~coreEnd: 이 조각이 책임지는 구간. sendStart~sendEnd: 앞뒤 30초 겹쳐서 실제로 보내는 구간. (ms) */
data class Chunk(val index: Int, val coreStart: Long, val coreEnd: Long, val sendStart: Long, val sendEnd: Long)

object Splitter {
    const val STEP = 500L
    private const val TARGET = 10 * 60_000L
    private const val OVERLAP = 30_000L
    private const val WINDOW = 30_000L

    fun readAmps(f: File): IntArray =
        if (!f.exists()) IntArray(0)
        else f.readLines().mapNotNull { it.trim().toIntOrNull() }.toIntArray()

    fun durationMs(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    /** 약 10분마다, 그 앞뒤 30초 안에서 가장 조용한 지점을 자르는 위치로 고름. */
    fun plan(dur: Long, amps: IntArray): List<Chunk> {
        val cuts = mutableListOf(0L)
        var next = TARGET
        while (dur - next > 2 * 60_000L) {
            val c = quietest(amps, next - WINDOW, next + WINDOW)
            cuts.add(c)
            next = c + TARGET
        }
        cuts.add(dur)
        return (0 until cuts.size - 1).map { i ->
            Chunk(
                i, cuts[i], cuts[i + 1],
                maxOf(0L, cuts[i] - OVERLAP),
                minOf(dur, cuts[i + 1] + OVERLAP)
            )
        }
    }

    private fun quietest(amps: IntArray, from: Long, to: Long): Long {
        var best = (from + to) / 2
        if (amps.isEmpty()) return best
        var bestV = Long.MAX_VALUE
        var t = from
        while (t <= to) {
            val i = (t / STEP).toInt()
            if (i >= 0 && i + 1 < amps.size) {
                val v = amps[i].toLong() + amps[i + 1]
                if (v < bestV) {
                    bestV = v
                    best = t
                }
            }
            t += STEP
        }
        return best
    }

    /** 재인코딩 없이 구간만 잘라 새 m4a로 저장. 실제 시작 시각(ms)을 돌려줌. */
    fun extract(src: File, startMs: Long, endMs: Long, out: File): Long {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) {
            ex.release()
            throw IllegalStateException("오디오 트랙을 찾을 수 없습니다")
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val t = mux.addTrack(fmt)
        mux.start()

        val endUs = endMs * 1000
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        var first = -1L
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val ts = ex.sampleTime
            if (ts > endUs) break
            if (first < 0) first = ts
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ts - first
            info.flags =
                if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(t, buf, info)
            ex.advance()
        }
        mux.stop()
        mux.release()
        ex.release()
        return if (first < 0) startMs else first / 1000
    }
}

/** 녹음 음량 기록으로 "말하고 있는 시간"을 추정. 기록이 없으면 전 구간을 말하는 중으로 봄. */
class Speech(private val amps: IntArray) {
    private val threshold: Int =
        if (amps.isEmpty()) 0
        else {
            val s = amps.sorted()
            maxOf(500, s[(s.size * 0.2).toInt().coerceAtMost(s.size - 1)] * 2)
        }

    fun speakingMs(from: Long, to: Long): Long {
        if (amps.isEmpty()) return (to - from).coerceAtLeast(0L)
        var n = 0L
        var i = (from / Splitter.STEP).toInt().coerceAtLeast(0)
        val end = (to / Splitter.STEP).toInt().coerceAtMost(amps.size)
        while (i < end) {
            if (amps[i] > threshold) n++
            i++
        }
        return n * Splitter.STEP
    }
}
