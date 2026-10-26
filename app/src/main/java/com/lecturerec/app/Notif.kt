package com.lecturerec.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notif {
    const val REC_ID = 1
    const val WORK_ID = 2
    private var doneId = 100

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "녹음", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("work", "전사 진행", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("done", "전사 완료", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun svc(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            c, code, Intent(c, RecordService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** elapsedMs: 지금까지 녹음된 길이(일시정지 시간 제외) */
    fun recording(c: Context, title: String, paused: Boolean = false, elapsedMs: Long = 0L): Notification {
        val b = NotificationCompat.Builder(c, "rec")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "일시정지됨" else "녹음 중")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(c))
        if (paused) {
            val s = elapsedMs / 1000
            b.setSubText("%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60))
            b.addAction(0, "계속", svc(c, RecordService.ACTION_RESUME, 11))
        } else {
            b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - elapsedMs)
            b.addAction(0, "일시정지", svc(c, RecordService.ACTION_PAUSE, 12))
        }
        b.addAction(0, "정지", svc(c, RecordService.ACTION_STOP, 13))
        return b.build()
    }

    fun working(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, "work")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("전사 중")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()

    fun done(c: Context, title: String, text: String) {
        try {
            val n = NotificationCompat.Builder(c, "done")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build()
            c.getSystemService(NotificationManager::class.java).notify(doneId++, n)
        } catch (e: SecurityException) {
            // 알림 권한이 없으면 조용히 넘어감
        }
    }
}
