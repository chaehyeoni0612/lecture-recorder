package com.lecturerec.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 평일 수업 시작 전(8:30 … 16:30) "녹음을 시작하시겠습니까?" 알림 */
object Reminder {
    private val TIMES = listOf(8, 9, 10, 11, 13, 14, 15, 16).map { LocalTime.of(it, 30) }

    fun next(now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var d = now.toLocalDate()
        while (true) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                for (t in TIMES) {
                    val at = d.atTime(t)
                    if (at.isAfter(now.plusSeconds(5))) return at
                }
            }
            d = d.plusDays(1)
        }
    }

    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 30, Intent(c, ReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pi(c))
        if (!Prefs.remindersOn(c)) return
        val at = next().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
    }

    fun show(c: Context) {
        if (RecordService.currentBase != null) return
        Notif.ensureChannels(c)
        val open = PendingIntent.getActivity(
            c, 31, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val start = PendingIntent.getActivity(
            c, 32,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("start_record", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val subject = Prefs.defaultSubject(c)
        val p = Store.periodOf(LocalDateTime.now().plusMinutes(40))
        val text = listOfNotNull(subject, if (p > 0) "${p}교시" else null).joinToString(", ")
        val n = NotificationCompat.Builder(c, "remind")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("녹음을 시작하시겠습니까?")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "녹음 시작", start)
            .setTimeoutAfter(50 * 60 * 1000L)
            .build()
        try {
            c.getSystemService(NotificationManager::class.java).notify(Notif.REMIND_ID, n)
        } catch (e: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Reminder.schedule(c)
            else -> {
                Reminder.show(c)
                Reminder.schedule(c)
            }
        }
    }
}
