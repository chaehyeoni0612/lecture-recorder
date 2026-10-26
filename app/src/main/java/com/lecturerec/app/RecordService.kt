package com.lecturerec.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 화면이 꺼져도 계속 녹음하는 포그라운드 서비스. 0.5초마다 음량을 .amp 파일에 기록. */
class RecordService : Service() {

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_SUBJECT = "subject"

        @Volatile var currentBase: String? = null
        @Volatile var startedAt: Long = 0L

        fun start(c: Context, subject: String) {
            val i = Intent(c, RecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SUBJECT, subject)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, RecordService::class.java).setAction(ACTION_STOP))
        }
    }

    private var recorder: MediaRecorder? = null
    private var ampWriter: BufferedWriter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val sampler = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val a = try { r.maxAmplitude } catch (e: Exception) { 0 }
            try {
                ampWriter?.write(a.toString())
                ampWriter?.newLine()
            } catch (e: Exception) { }
            handler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRec(intent.getStringExtra(EXTRA_SUBJECT) ?: "강의")
            ACTION_STOP -> stopRec()
        }
        return START_NOT_STICKY
    }

    private fun makeBase(subject: String): String {
        val now = LocalDateTime.now()
        val clean = subject.replace(Regex("""[\\/:*?"<>|]"""), "").trim().ifEmpty { "강의" }
        val name = clean + "_" + now.format(DateTimeFormatter.ofPattern("yyMMdd")) +
            "_" + now.format(DateTimeFormatter.ofPattern("HH")) + "시" +
            now.format(DateTimeFormatter.ofPattern("mm")) + "분"
        var base = name
        var n = 2
        while (Store.audio(this, base).exists()) {
            base = "${name}_$n"
            n++
        }
        return base
    }

    private fun startRec(subject: String) {
        if (recorder != null) return
        Notif.ensureChannels(this)
        val base = makeBase(subject)
        val notif = Notif.recording(this, base)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(Notif.REC_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notif.REC_ID, notif)
        }

        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(Store.audio(this, base).absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recorder = r
        ampWriter = Store.amp(this, base).bufferedWriter()
        currentBase = base
        startedAt = System.currentTimeMillis()
        Store.writeState(this, base, "recording")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lecturerec:rec").apply {
            acquire(5 * 60 * 60 * 1000L)
        }
        handler.post(sampler)
    }

    private fun stopRec() {
        handler.removeCallbacks(sampler)
        val base = currentBase
        recorder?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        recorder = null
        try { ampWriter?.close() } catch (e: Exception) { }
        ampWriter = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        currentBase = null
        startedAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (base != null) {
            Store.writeState(this, base, "queued", "인터넷 연결되면 시작")
            TranscribeWorker.enqueue(this, base)
        }
    }

    override fun onDestroy() {
        if (recorder != null) stopRec()
        super.onDestroy()
    }
}
