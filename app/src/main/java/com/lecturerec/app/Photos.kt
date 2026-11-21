package com.lecturerec.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 갤러리 "강의녹음" 앨범 사진 다루기 */
object Photos {
    const val FOLDER = "Pictures/강의녹음"

    data class Photo(val uri: Uri, val name: String, val date: Long)

    /** 새 사진 이름: 과목_YYMMDD_N교시_HHmmss */
    fun newName(c: Context, suffix: String = ""): String {
        val now = LocalDateTime.now()
        val rec = RecordService.currentBase
        val subject = (if (rec != null) SyncStore.subjectOf(rec) else null)
            ?: Prefs.defaultSubject(c) ?: "강의"
        val p = Store.periodOf(now)
        return subject.replace(Regex("""[\\/:*?"<>|]"""), "") + "_" +
            now.format(DateTimeFormatter.ofPattern("yyMMdd")) + "_" +
            (if (p > 0) "${p}교시_" else "") +
            now.format(DateTimeFormatter.ofPattern("HHmmss")) + suffix
    }

    fun values(name: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
    }

    fun list(c: Context): List<Photo> {
        val out = mutableListOf<Photo>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("$FOLDER%")
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/강의녹음/%")
        }
        c.contentResolver.query(col, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                out.add(Photo(ContentUris.withAppendedId(col, id), cur.getString(1) ?: "", cur.getLong(2)))
            }
        }
        return out
    }

    /** 긴 변이 max 정도가 되게 줄여서 읽기 */
    fun load(c: Context, uri: Uri, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val rot = try {
            c.contentResolver.openInputStream(uri)?.use {
                when (androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        if (rot == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rot) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun saveBitmap(c: Context, bmp: Bitmap, name: String): Uri? {
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values(name)) ?: return null
        c.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return uri
    }

    fun saveFrom(c: Context, src: Uri, name: String): Uri? {
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values(name)) ?: return null
        c.contentResolver.openInputStream(src)?.use { i -> c.contentResolver.openOutputStream(uri)?.use { i.copyTo(it) } }
        return uri
    }

    /** 사진을 클립보드에 (앱 캐시에 복사본을 두고 그 주소를 넣음). 메인 스레드 아닌 곳에서 */
    fun copyToClipboardFile(c: Context, src: Uri): File {
        val dir = File(c.cacheDir, "clip").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        c.contentResolver.openInputStream(src)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    fun setClip(c: Context, f: File) {
        val u = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(c.contentResolver, "사진", u))
    }

    fun shareUri(c: Context, f: File): Uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
}
