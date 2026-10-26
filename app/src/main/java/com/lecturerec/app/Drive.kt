package com.lecturerec.app

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NeedsReconnect : Exception("드라이브 다시 연결 필요")

/** Google 드라이브 REST (drive.file 권한: 이 앱이 만든 파일만 보임) */
object Drive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UP = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "application/vnd.google-apps.folder"
    const val ROOT_NAME = "강의녹음"

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** 백그라운드에서 조용히 토큰 받기. 사용자 확인이 필요하면 NeedsReconnect */
    fun token(c: Context): String {
        val r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()))
        if (r.hasResolution()) throw NeedsReconnect()
        return r.accessToken ?: throw NeedsReconnect()
    }

    data class Item(val id: String, val name: String, val mime: String, val modified: String)

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun open(token: String, url: String, method: String): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = method
        con.connectTimeout = 30_000
        con.readTimeout = 300_000
        con.setRequestProperty("Authorization", "Bearer $token")
        return con
    }

    private fun readOrThrow(con: HttpURLConnection): String {
        val code = con.responseCode
        val body = (if (code in 200..299) con.inputStream else con.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code == 401) throw NeedsReconnect()
        if (code !in 200..299) throw ApiException(code, "드라이브 오류 $code: ${body.take(150)}")
        return body
    }

    private fun json(token: String, url: String, method: String, body: JSONObject? = null): JSONObject {
        val con = open(token, url, method)
        try {
            if (body != null) {
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val s = readOrThrow(con)
            return if (s.isBlank()) JSONObject() else JSONObject(s)
        } finally {
            con.disconnect()
        }
    }

    fun email(token: String): String =
        json(token, "$API/about?fields=user(emailAddress)", "GET").optJSONObject("user")?.optString("emailAddress") ?: ""

    fun list(token: String, parent: String): List<Item> {
        val out = mutableListOf<Item>()
        var page: String? = null
        do {
            val qs = "'${esc(parent)}' in parents and trashed=false"
            var url = "$API/files?q=${q(qs)}&pageSize=1000&fields=${q("nextPageToken,files(id,name,mimeType,modifiedTime)")}"
            if (page != null) url += "&pageToken=${q(page)}"
            val o = json(token, url, "GET")
            val fs = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until fs.length()) {
                val f = fs.getJSONObject(i)
                out.add(Item(f.getString("id"), f.getString("name"), f.optString("mimeType"), f.optString("modifiedTime")))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** parent 안의 폴더를 찾고 없으면 만듦. parent가 null이면 내 드라이브 최상위 */
    fun folder(token: String, name: String, parent: String?): String {
        val p = parent ?: "root"
        val qs = "name='${esc(name)}' and mimeType='$FOLDER' and '${esc(p)}' in parents and trashed=false"
        val fs = json(token, "$API/files?q=${q(qs)}&fields=files(id)", "GET").optJSONArray("files")
        if (fs != null && fs.length() > 0) return fs.getJSONObject(0).getString("id")
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(p))
        return json(token, "$API/files?fields=id", "POST", meta).getString("id")
    }

    /** 새로 올리거나(existingId == null) 내용 교체. 결과: (id, modifiedTime) */
    fun upload(token: String, file: File, mime: String, parent: String, existingId: String?): Pair<String, String> {
        val start = if (existingId == null) {
            open(token, "$UP/files?uploadType=resumable&fields=id,modifiedTime", "POST")
        } else {
            open(token, "$UP/files/$existingId?uploadType=resumable&fields=id,modifiedTime", "PATCH")
        }
        val location: String
        try {
            val meta = if (existingId == null) {
                JSONObject().put("name", file.name).put("parents", JSONArray().put(parent))
            } else {
                JSONObject()
            }
            start.doOutput = true
            start.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            start.setRequestProperty("X-Upload-Content-Type", mime)
            start.setRequestProperty("X-Upload-Content-Length", file.length().toString())
            start.outputStream.use { it.write(meta.toString().toByteArray(Charsets.UTF_8)) }
            readOrThrow(start)
            location = start.getHeaderField("Location") ?: throw ApiException(-1, "업로드 주소를 받지 못했습니다")
        } finally {
            start.disconnect()
        }
        val put = open(token, location, "PUT")
        try {
            put.doOutput = true
            put.setFixedLengthStreamingMode(file.length())
            put.setRequestProperty("Content-Type", mime)
            file.inputStream().use { input -> put.outputStream.use { input.copyTo(it) } }
            val o = JSONObject(readOrThrow(put))
            return o.getString("id") to o.optString("modifiedTime")
        } finally {
            put.disconnect()
        }
    }

    fun download(token: String, id: String, dest: File) {
        val con = open(token, "$API/files/$id?alt=media", "GET")
        try {
            val code = con.responseCode
            if (code == 401) throw NeedsReconnect()
            if (code !in 200..299) throw ApiException(code, "내려받기 실패 $code")
            val tmp = File(dest.path + ".part")
            con.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } finally {
            con.disconnect()
        }
    }

    fun delete(token: String, id: String) {
        val con = open(token, "$API/files/$id", "DELETE")
        try {
            val code = con.responseCode
            if (code == 401) throw NeedsReconnect()
            if (code !in 200..299 && code != 404) throw ApiException(code, "삭제 실패 $code")
        } finally {
            con.disconnect()
        }
    }
}

/** 녹음별 드라이브 상태를 기기에 기록 (sync.json) */
object SyncStore {
    data class Rec(
        var audioId: String = "",
        var txtId: String = "",
        var txtRemoteModified: String = "",
        var txtSyncedLocalMtime: Long = 0L
    )

    private fun file(c: Context) = File(Store.dir(c), "sync.json")

    @Synchronized
    fun load(c: Context): JSONObject {
        val f = file(c)
        return try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }
    }

    @Synchronized
    private fun save(c: Context, o: JSONObject) = file(c).writeText(o.toString())

    fun get(c: Context, base: String): Rec {
        val o = load(c).optJSONObject("recs")?.optJSONObject(base) ?: return Rec()
        return Rec(o.optString("audioId"), o.optString("txtId"), o.optString("txtMod"), o.optLong("txtLocal"))
    }

    @Synchronized
    fun put(c: Context, base: String, r: Rec) {
        val all = load(c)
        val recs = all.optJSONObject("recs") ?: JSONObject().also { all.put("recs", it) }
        recs.put(base, JSONObject().put("audioId", r.audioId).put("txtId", r.txtId)
            .put("txtMod", r.txtRemoteModified).put("txtLocal", r.txtSyncedLocalMtime))
        save(c, all)
    }

    @Synchronized
    fun remove(c: Context, base: String) {
        val all = load(c)
        all.optJSONObject("recs")?.remove(base)
        save(c, all)
    }

    /** "이 기기에서만 삭제"한 녹음: 다시 내려받지 않음 */
    @Synchronized
    fun ignore(c: Context, base: String) {
        val all = load(c)
        val arr = all.optJSONArray("ignored") ?: JSONArray().also { all.put("ignored", it) }
        arr.put(base)
        save(c, all)
    }

    fun ignored(c: Context): Set<String> {
        val arr = load(c).optJSONArray("ignored") ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    /** 파일 이름에서 과목 뽑기: 과목_YYMMDD_HH시MM분(_n) */
    fun subjectOf(base: String): String =
        Regex("""^(.*)_\d{6}_\d{2}시\d{2}분(_\d+)?$""").find(base)?.groupValues?.get(1)?.ifBlank { null } ?: "기타"
}
