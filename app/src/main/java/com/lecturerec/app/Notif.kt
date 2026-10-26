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

    fun recording(c: Context, title: String): Notification =
        NotificationCompat.Builder(c, "rec")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("녹음 중")
            .setContentText(title)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(openApp(c))
            .build()

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
