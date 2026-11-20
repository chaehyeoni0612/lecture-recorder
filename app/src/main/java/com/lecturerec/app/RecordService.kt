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
import java.io.FileOutputStream
import java.time.LocalDateTime

/**
 * 화면이 꺼져도 계속 녹음하는 포그라운드 서비스. 0.5초마다 음량을 .amp 파일에 기록.
 * 녹음은 AAC(ADTS)로 파일 끝에 계속 덧붙여 씀 → 앱이 강제로 꺼져도 끊기기 직전까지 남음.
 * 전사할 때 m4a로 옮김(.src → .m4a).
 */
class RecordService : Service() {

    companion object {
        private const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        private const val EXTRA_SUBJECT = "subject"
        private const val EXTRA_PERIOD = "period"
        const val ACTION_RENOTIFY = "renotify"

        @Volatile var currentBase: String? = null
        @Volatile var startedAt: Long = 0L
        @Volatile var paused: Boolean = false
        @Volatile var pausedTotal: Long = 0L
        @Volatile var pausedAt: Long = 0L

        /** 녹음된 길이(일시정지 시간 제외) */
        fun elapsedMs(): Long {
            if (currentBase == null) return 0L
            val now = System.currentTimeMillis()
            return now - startedAt - pausedTotal - (if (paused) now - pausedAt else 0L)
        }

        fun pause(c: Context) = c.startService(Intent(c, RecordService::class.java).setAction(ACTION_PAUSE))
        fun resume(c: Context) = c.startService(Intent(c, RecordService::class.java).setAction(ACTION_RESUME))

        fun start(c: Context, subject: String, period: Int? = null) {
            val i = Intent(c, RecordService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SUBJECT, subject)
                .putExtra(EXTRA_PERIOD, period ?: -1)
            ContextCompat.startForegroundService(c, i)
        }

        private fun sp(c: Context) = c.getSharedPreferences("rec", Context.MODE_PRIVATE)
        /** 앱이 꺼졌다 다시 켜질 때 이어서 녹음하려고 기록해 두는 현재 녹음 */
        fun savedBase(c: Context): String? = sp(c).getString("base", null)
        fun clearSaved(c: Context) = sp(c).edit().clear().apply()

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
            if (paused) {
                // 일시정지 중엔 녹음 파일에도 시간이 안 쌓이므로 음량 기록도 멈춤
                handler.postDelayed(this, 500)
                return
            }
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
        if (intent == null) {
            if (recorder == null && !resumeAfterKill()) {
                Recovery.run(this)
                stopSelf()
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> startRec(
                intent.getStringExtra(EXTRA_SUBJECT) ?: "강의",
                intent.getIntExtra(EXTRA_PERIOD, -1).takeIf { it >= 0 }
            )
            ACTION_RENOTIFY -> updateNotif()
            ACTION_STOP -> stopRec()
            ACTION_PAUSE -> pauseRec()
            ACTION_RESUME -> resumeRec()
        }
        // 시스템이 앱을 꺼도 다시 켜지게 (다시 켜지면 하던 녹음 이어서)
        return START_STICKY
    }

    private var out: FileOutputStream? = null
    private var restarts = 0

    private fun goForeground(base: String) {
        val notif = Notif.recording(this, base, paused, elapsedMs())
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(Notif.REC_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notif.REC_ID, notif)
        }
    }

    /** base.src 끝에 이어서 녹음 시작 */
    private fun openRecorder(base: String): Boolean {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        val fos = FileOutputStream(Store.src(this, base), true)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(32000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(fos.fd)
            r.setOnErrorListener { _, _, _ -> handler.post { restartRecorder() } }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            try { fos.close() } catch (x: Exception) { }
            return false
        }
        recorder = r
        out = fos
        return true
    }

    private fun closeRecorder() {
        recorder?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        recorder = null
        try { out?.flush(); out?.close() } catch (e: Exception) { }
        out = null
    }

    /** 녹음기가 오류로 멈추면 같은 파일에 이어서 다시 시작 */
    private fun restartRecorder() {
        val base = currentBase ?: return
        closeRecorder()
        restarts++
        if (restarts <= 20 && openRecorder(base)) {
            if (paused) try { recorder?.pause() } catch (e: Exception) { }
            Notif.done(this, "녹음이 잠깐 끊겨 다시 시작했습니다", base)
        } else {
            Notif.done(this, "녹음이 멈췄습니다", "$base, 여기까지는 저장됐습니다")
            stopRec()
        }
    }

    private fun startRec(subject: String, period: Int?) {
        if (recorder != null) return
        Notif.ensureChannels(this)
        val base = Store.newBase(this, subject, LocalDateTime.now(), period)
        currentBase = base
        startedAt = System.currentTimeMillis()
        paused = false
        pausedTotal = 0L
        goForeground(base)
        if (!openRecorder(base)) {
            currentBase = null
            Store.src(this, base).delete()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        begin(base, append = false)
    }

    /** 앱이 꺼졌다 다시 켜졌을 때: 하던 녹음에 이어서 */
    private fun resumeAfterKill(): Boolean {
        val base = savedBase(this) ?: return false
        if (Store.readState(this, base).status != "recording") { clearSaved(this); return false }
        Notif.ensureChannels(this)
        currentBase = base
        startedAt = getSharedPreferences("rec", MODE_PRIVATE).getLong("started", System.currentTimeMillis())
        paused = false
        pausedTotal = 0L
        try { goForeground(base) } catch (e: Exception) { currentBase = null; return false }
        if (!openRecorder(base)) {
            currentBase = null
            return false
        }
        begin(base, append = true)
        Notif.done(this, "앱이 꺼졌다 켜져서 녹음을 이어서 합니다", base)
        return true
    }

    private fun begin(base: String, append: Boolean) {
        ampWriter = java.io.BufferedWriter(java.io.FileWriter(Store.amp(this, base), append))
        Store.writeState(this, base, "recording")
        getSharedPreferences("rec", MODE_PRIVATE).edit()
            .putString("base", base).putLong("started", startedAt).apply()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lecturerec:rec").apply {
            acquire(5 * 60 * 60 * 1000L)
        }
        handler.post(sampler)
        handler.post(flusher)
    }

    /** 음량 기록을 30초마다 파일에 내려씀 (꺼져도 남게) */
    private val flusher = object : Runnable {
        override fun run() {
            if (currentBase == null) return
            try { ampWriter?.flush() } catch (e: Exception) { }
            handler.postDelayed(this, 30_000)
        }
    }

    private fun updateNotif() {
        val base = currentBase ?: return
        val n = Notif.recording(this, base, paused, elapsedMs())
        getSystemService(android.app.NotificationManager::class.java).notify(Notif.REC_ID, n)
    }

    private fun pauseRec() {
        val r = recorder ?: return
        if (paused) return
        try { r.pause() } catch (e: Exception) { return }
        pausedAt = System.currentTimeMillis()
        paused = true
        currentBase?.let { Store.writeState(this, it, "recording", "일시정지") }
        updateNotif()
    }

    private fun resumeRec() {
        val r = recorder ?: return
        if (!paused) return
        try { r.resume() } catch (e: Exception) { return }
        pausedTotal += System.currentTimeMillis() - pausedAt
        paused = false
        currentBase?.let { Store.writeState(this, it, "recording") }
        updateNotif()
    }

    private fun stopRec() {
        handler.removeCallbacks(sampler)
        handler.removeCallbacks(flusher)
        val base = currentBase
        closeRecorder()
        try { ampWriter?.close() } catch (e: Exception) { }
        ampWriter = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        currentBase = null
        startedAt = 0L
        paused = false
        pausedTotal = 0L
        pausedAt = 0L
        restarts = 0
        clearSaved(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (base != null) {
            Store.writeState(this, base, "queued", "인터넷 연결되면 시작")
            TranscribeWorker.enqueue(this, base)
            SyncWorker.enqueue(this)
        }
    }

    override fun onDestroy() {
        if (recorder != null) {
            // 시스템이 서비스를 끄는 경우: 파일은 이미 계속 저장되고 있으므로 닫기만 하고,
            // 다시 켜지면 이어서 녹음. (사용자가 정지한 게 아니므로 전사로 넘기지 않음)
            handler.removeCallbacks(sampler)
            handler.removeCallbacks(flusher)
            closeRecorder()
            try { ampWriter?.close() } catch (e: Exception) { }
            ampWriter = null
            wakeLock?.let { if (it.isHeld) it.release() }
            currentBase = null
        }
        super.onDestroy()
    }
}
