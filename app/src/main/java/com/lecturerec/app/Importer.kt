package com.lecturerec.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Importer {

    /** 원본을 앱 폴더에 복사만 하고 전사 작업에 넘김. 변환은 전사 작업이 백그라운드에서 함. */
    fun import(c: Context, uri: Uri, subject: String): String {
        val (name, modified) = describe(c, uri)
        val time = timeFromName(name)
            ?: modified?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
            ?: LocalDateTime.now()
        val base = Store.newBase(c, subject, time)
        val dst = Store.src(c, base)
        c.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "파일을 열 수 없습니다" }
            dst.outputStream().use { input.copyTo(it) }
        }
        Store.writeState(c, base, "queued", "불러옴 · 변환 후 전사")
        TranscribeWorker.enqueue(c, base)
        return base
    }

    private fun describe(c: Context, uri: Uri): Pair<String, Long?> {
        var name = ""
        var modified: Long? = null
        try {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = cur.getString(ni) ?: ""
                    val mi = cur.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (mi >= 0 && !cur.isNull(mi)) modified = cur.getLong(mi).takeIf { it > 0 }
                }
            }
        } catch (e: Exception) { }
        return name to modified
    }

    /** 삼성 녹음 앱처럼 파일 이름에 251026_175200 형태가 있으면 그 시각을 씀 */
    private fun timeFromName(name: String): LocalDateTime? {
        val m = Regex("""(\d{6})_(\d{4})""").find(name) ?: return null
        return try {
            LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyMMddHHmm"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 어떤 형식이든 → 모노 AAC m4a. 0.5초마다 최대 음량을 amp 파일에 기록(앱 녹음과 같은 형식).
     */
    /**
     * 불러온 파일 준비. 이미 AAC(m4a)면 다시 인코딩하지 않고 그대로 옮기고 소리 크기만 기록(빠름).
     * 그 외 형식만 다시 인코딩. progress: 0~100
     */
    fun prepare(src: File, out: File, ampFile: File, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var mime = ""
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("audio/")) { mime = m; break }
        }
        ex.release()
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            remux(src, out)
            try {
                ampOnly(src, ampFile, progress)
            } catch (e: Exception) {
                ampFile.delete() // 소리 크기 기록이 없어도 전사는 됨
            }
        } else {
            transcode(src, out, ampFile, progress)
        }
    }

    /** 다시 인코딩 없이 오디오 트랙만 새 m4a로 옮김 */
    private fun remux(src: File, out: File) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
        }
        ex.selectTrack(track)
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val t = mux.addTrack(ex.getTrackFormat(track))
        mux.start()
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(t, buf, info)
                ex.advance()
            }
        } finally {
            try { mux.stop() } catch (e: Exception) { }
            mux.release()
            ex.release()
        }
    }

    /** 디코딩만 해서 0.5초마다 최대 음량 기록 (샘플을 듬성듬성 봐서 빠르게) */
    private fun ampOnly(src: File, ampFile: File, progress: (Int) -> Unit) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
        }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt, null, null, 0)
        dec.start()
        val amps = IntArray(((durUs / 500_000L) + 4).toInt().coerceAtLeast(16))
        var isFloat = false
        var lastPct = -1
        val info = MediaCodec.BufferInfo()
        var inEOS = false
        try {
            while (true) {
                if (!inEOS) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEOS = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val b = dec.getOutputBuffer(o)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val bb = b.slice().order(ByteOrder.nativeOrder())
                        var mx = 0
                        if (isFloat) {
                            val fb = bb.asFloatBuffer()
                            var k = 0
                            while (k < fb.limit()) {
                                val v = (kotlin.math.abs(fb.get(k)) * 32767f).toInt()
                                if (v > mx) mx = v
                                k += 8
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            var k = 0
                            while (k < sb.limit()) {
                                val v = kotlin.math.abs(sb.get(k).toInt())
                                if (v > mx) mx = v
                                k += 8
                            }
                        }
                        val w = (info.presentationTimeUs / 500_000L).toInt()
                        if (w in amps.indices && mx > amps[w]) amps[w] = mx
                        if (durUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                    dec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } finally {
            try { dec.stop() } catch (e: Exception) { }
            dec.release()
            ex.release()
        }
        ampFile.bufferedWriter().use { w -> amps.forEach { w.write(it.toString()); w.newLine() } }
    }

    fun transcode(src: File, out: File, ampFile: File, progress: (Int) -> Unit = {}) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) {
            ex.release()
            throw IllegalStateException("오디오가 없는 파일입니다")
        }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var lastPct = -1
        val dec = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(inFmt, null, null, 0)
        dec.start()

        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false

        var enc: MediaCodec? = null
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false

        val queue = ArrayDeque<ShortArray>()
        var headPos = 0
        var samplesFed = 0L

        val ampWriter = ampFile.bufferedWriter()
        var ampMax = 0
        var ampN = 0

        val info = MediaCodec.BufferInfo()
        var inEOS = false
        var decEOS = false
        var encEosQueued = false
        var encEOS = false

        try {
            while (!encEOS) {
                // 1) 원본 → 디코더
                if (!inEOS) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = dec.getInputBuffer(i)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEOS = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                // 2) 디코더 → 모노 PCM
                if (!decEOS) {
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (o >= 0) {
                        if (enc == null) enc = makeEncoder(rate)
                        if (durUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durUs).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                        val b = dec.getOutputBuffer(o)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val mono = toMono(b.slice(), channels, isFloat)
                        if (mono.isNotEmpty()) {
                            queue.addLast(mono)
                            for (v in mono) {
                                val a = if (v < 0) -v.toInt() else v.toInt()
                                if (a > ampMax) ampMax = a
                                ampN++
                                if (ampN >= rate / 2) {
                                    ampWriter.write(ampMax.toString())
                                    ampWriter.newLine()
                                    ampMax = 0
                                    ampN = 0
                                }
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decEOS = true
                    }
                }

                val e = enc
                if (e == null) {
                    if (decEOS) throw IllegalStateException("소리를 읽을 수 없는 파일입니다")
                    continue
                }

                // 3) 모노 PCM → 인코더
                if (!encEosQueued && (queue.isNotEmpty() || decEOS)) {
                    val i = e.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = e.getInputBuffer(i)!!
                        b.clear()
                        b.order(ByteOrder.nativeOrder())
                        val pts = samplesFed * 1_000_000L / rate
                        if (queue.isEmpty()) {
                            e.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encEosQueued = true
                        } else {
                            val sb = b.asShortBuffer()
                            var written = 0
                            while (queue.isNotEmpty() && sb.hasRemaining()) {
                                val head = queue.first()
                                val n = minOf(head.size - headPos, sb.remaining())
                                sb.put(head, headPos, n)
                                headPos += n
                                written += n
                                if (headPos >= head.size) {
                                    queue.removeFirst()
                                    headPos = 0
                                }
                            }
                            e.queueInputBuffer(i, 0, written * 2, pts, 0)
                            samplesFed += written
                        }
                    }
                }

                // 4) 인코더 → m4a
                val o = e.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(e.outputFormat)
                    mux.start()
                    muxStarted = true
                } else if (o >= 0) {
                    val b = e.getOutputBuffer(o)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        mux.writeSampleData(muxTrack, b, info)
                    }
                    e.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEOS = true
                }
            }
            if (ampN > 0) {
                ampWriter.write(ampMax.toString())
                ampWriter.newLine()
            }
        } finally {
            try { ampWriter.close() } catch (x: Exception) { }
            try { dec.stop() } catch (x: Exception) { }
            dec.release()
            enc?.let {
                try { it.stop() } catch (x: Exception) { }
                it.release()
            }
            try { if (muxStarted) mux.stop() } catch (x: Exception) { }
            mux.release()
            ex.release()
        }
    }

    private fun makeEncoder(rate: Int): MediaCodec {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        return e
    }

    private fun toMono(b: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        b.order(ByteOrder.nativeOrder())
        val ch = channels.coerceAtLeast(1)
        return if (isFloat) {
            val fb = b.asFloatBuffer()
            val frames = fb.remaining() / ch
            ShortArray(frames) { f ->
                var sum = 0f
                for (k in 0 until ch) sum += fb.get(f * ch + k)
                ((sum / ch).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val sb = b.asShortBuffer()
            val frames = sb.remaining() / ch
            ShortArray(frames) { f ->
                var sum = 0
                for (k in 0 until ch) sum += sb.get(f * ch + k)
                (sum / ch).toShort()
            }
        }
    }
}
