package com.lecturerec.app

import android.content.Context
import java.time.LocalDate

data class ScheduleEntry(val subject: String, val start: LocalDate, val end: LocalDate)

object Prefs {
    const val DEFAULT_MODELS = "gemini-flash-latest\ngemini-2.5-flash"
    const val DEFAULT_SCHEDULE =
        "내분비학 2차,2026-10-01,2026-11-05\n" +
        "혈액종양 1차,2026-11-06,2026-11-26\n" +
        "혈액종양 2차,2026-11-27,2026-12-10"

    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = (sp(c).getString("api_key", "") ?: "").trim()
    fun modelsText(c: Context): String = sp(c).getString("models", DEFAULT_MODELS) ?: DEFAULT_MODELS
    fun scheduleText(c: Context): String = sp(c).getString("schedule", DEFAULT_SCHEDULE) ?: DEFAULT_SCHEDULE

    fun models(c: Context): List<String> =
        modelsText(c).lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun schedule(c: Context): List<ScheduleEntry> =
        scheduleText(c).lines().mapNotNull { line ->
            val p = line.split(",")
            if (p.size < 3) return@mapNotNull null
            try {
                ScheduleEntry(p[0].trim(), LocalDate.parse(p[1].trim()), LocalDate.parse(p[2].trim()))
            } catch (e: Exception) {
                null
            }
        }

    fun subjects(c: Context): List<String> = schedule(c).map { it.subject }.distinct()

    fun defaultSubject(c: Context, today: LocalDate = LocalDate.now()): String? =
        schedule(c).firstOrNull { !today.isBefore(it.start) && !today.isAfter(it.end) }?.subject

    fun save(c: Context, key: String, models: String, schedule: String) {
        sp(c).edit()
            .putString("api_key", key.trim())
            .putString("models", models.trim())
            .putString("schedule", schedule.trim())
            .apply()
    }
}
