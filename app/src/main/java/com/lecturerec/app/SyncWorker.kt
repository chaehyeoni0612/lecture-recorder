package com.lecturerec.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object DrivePrefs {
    private fun sp(c: Context) = c.getSharedPreferences("drive", Context.MODE_PRIVATE)
    fun connected(c: Context) = sp(c).getBoolean("connected", false)
    fun email(c: Context) = sp(c).getString("email", "") ?: ""
    fun needsReconnect(c: Context) = sp(c).getBoolean("reconnect", false)
    fun lastSync(c: Context) = sp(c).getString("last", "") ?: ""
    fun rootId(c: Context) = sp(c).getString("root", "") ?: ""
    fun autoClean(c: Context) = sp(c).getBoolean("clean", true)
    fun setAutoClean(c: Context, v: Boolean) = sp(c).edit().putBoolean("clean", v).apply()
    fun setConnected(c: Context, on: Boolean, email: String = "") {
        sp(c).edit().putBoolean("connected", on).putString("email", email).putBoolean("reconnect", false)
            .apply { if (!on) remove("root") }.apply()
    }
    fun setEmail(c: Context, e: String) = sp(c).edit().putString("email", e).apply()
    fun setReconnect(c: Context, v: Boolean) = sp(c).edit().putBoolean("reconnect", v).apply()
    fun setLast(c: Context, v: String) = sp(c).edit().putString("last", v).apply()
    fun setRoot(c: Context, v: String) = sp(c).edit().putString("root", v).apply()
}

/** 기기 ↔ 드라이브 동기화: 올릴 것 올리고, 다른 기기에서 생긴 것 받아옴 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        fun enqueue(c: Context) {
            if (!DrivePrefs.connected(c)) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork("drive_sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        if (!DrivePrefs.connected(c)) return@withContext Result.success()
        try {
            val token = Drive.token(c)
            DrivePrefs.setReconnect(c, false)
            if (DrivePrefs.email(c).isEmpty()) {
                try { DrivePrefs.setEmail(c, Drive.email(token)) } catch (e: Exception) { }
            }
            val root = DrivePrefs.rootId(c).ifEmpty {
                Drive.folder(token, Drive.ROOT_NAME, null).also { DrivePrefs.setRoot(c, it) }
            }
            val folders = mutableMapOf<String, String>()
            fun folderFor(subject: String) = folders.getOrPut(subject) { Drive.folder(token, subject, root) }

            upload(c, token, ::folderFor)
            cleanLocal(c)
            download(c, token, root)
            DrivePrefs.setLast(c, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
            Result.success()
        } catch (e: NeedsReconnect) {
            DrivePrefs.setReconnect(c, true)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun upload(c: Context, token: String, folderFor: (String) -> String) {
        for (base in Store.list(c)) {
            val st = Store.readState(c, base).status
            if (st == "recording") continue
            val rec = SyncStore.get(c, base)
            val audio = Store.audio(c, base)
            val txt = Store.txt(c, base)
            var changed = false
            if (audio.exists() && rec.audioId.isEmpty() && !Store.src(c, base).exists()) {
                rec.audioId = Drive.upload(token, audio, "audio/mp4", folderFor(SyncStore.subjectOf(base)), null).first
                changed = true
            }
            if (txt.exists() && txt.lastModified() != rec.txtSyncedLocalMtime) {
                val (id, mod) = Drive.upload(
                    token, txt, "text/plain", folderFor(SyncStore.subjectOf(base)), rec.txtId.ifEmpty { null }
                )
                rec.txtId = id
                rec.txtRemoteModified = mod
                rec.txtSyncedLocalMtime = txt.lastModified()
                changed = true
            }
            if (changed) SyncStore.put(c, base, rec)
        }
    }

    private fun cleanLocal(c: Context) {
        for (base in Store.list(c)) DriveNow.cleanOne(c, base)
    }

    private fun download(c: Context, token: String, root: String) {
        val ignored = SyncStore.ignored(c)
        val localBases = Store.list(c).toSet()
        for (folder in Drive.list(token, root).filter { it.mime == "application/vnd.google-apps.folder" }) {
            val files = Drive.list(token, folder.id)
            val byBase = files.groupBy { it.name.substringBeforeLast('.') }
            for ((base, items) in byBase) {
                if (base in ignored) continue
                val a = items.firstOrNull { it.name.endsWith(".m4a") }
                val t = items.firstOrNull { it.name.endsWith(".txt") }
                val rec = SyncStore.get(c, base)
                var changed = false
                if (a != null && rec.audioId.isEmpty()) {
                    rec.audioId = a.id
                    changed = true
                }
                if (t != null) {
                    val local = Store.txt(c, base)
                    val noLocalEdits = !local.exists() || local.lastModified() == rec.txtSyncedLocalMtime
                    if ((rec.txtId.isEmpty() || t.modified > rec.txtRemoteModified) && noLocalEdits) {
                        Drive.download(token, t.id, local)
                        rec.txtId = t.id
                        rec.txtRemoteModified = t.modified
                        rec.txtSyncedLocalMtime = local.lastModified()
                        changed = true
                    }
                }
                if (base !in localBases && (a != null || t != null)) {
                    Store.writeState(c, base, "done", "")
                    changed = true
                }
                if (changed) SyncStore.put(c, base, rec)
            }
        }
    }
}

/** 상세 화면 등에서 바로 쓰는 동기 작업 (메인 스레드 금지) */
object DriveNow {
    @Volatile var openBase: String? = null

    /** 드라이브에 올라갔고 전사가 끝난 녹음은 기기에서 지움 (설정이 켜져 있을 때) */
    fun cleanOne(c: Context, base: String) {
        if (!DrivePrefs.connected(c) || !DrivePrefs.autoClean(c)) return
        if (base == openBase) return
        val audio = Store.audio(c, base)
        if (!audio.exists() || Store.src(c, base).exists()) return
        if (Store.readState(c, base).status != "done") return
        if (SyncStore.get(c, base).audioId.isEmpty()) return
        audio.delete()
    }

    fun fetchAudio(c: Context, base: String): Boolean {
        val rec = SyncStore.get(c, base)
        if (rec.audioId.isEmpty()) return false
        Drive.download(Drive.token(c), rec.audioId, Store.audio(c, base))
        return true
    }

    fun deleteRemote(c: Context, base: String) {
        val rec = SyncStore.get(c, base)
        val token = Drive.token(c)
        if (rec.audioId.isNotEmpty()) Drive.delete(token, rec.audioId)
        if (rec.txtId.isNotEmpty()) Drive.delete(token, rec.txtId)
    }
}
