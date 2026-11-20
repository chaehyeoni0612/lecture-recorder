package com.lecturerec.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var subjectBtn: MaterialButton
    private lateinit var timerText: TextView
    private lateinit var recBtn: MaterialButton
    private lateinit var pauseBtn: MaterialButton
    private lateinit var driveNote: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var listBox: LinearLayout
    private var subject: String = "강의"
    /** 직접 고른 교시 (null이면 시작 시각으로 자동) */
    private var manualPeriod: Int? = null
    private lateinit var periodBtn: MaterialButton
    private var defaultRecTint: ColorStateList? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastListSignature = ""

    private val ticker = object : Runnable {
        override fun run() {
            updateRecUi()
            handler.postDelayed(this, 1000)
        }
    }
    private val refresher = object : Runnable {
        override fun run() {
            refreshList(false)
            handler.postDelayed(this, 3000)
        }
    }

    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importUris(uris)
        }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.ensureChannels(this)
        title = "강의녹음"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        // 녹음 영역
        root.addView(Ui.section(this, "과목, 교시"))
        subjectBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 14f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setAllCaps(false)
            setOnClickListener { chooseSubject() }
        }
        periodBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 14f
            setAllCaps(false)
            setOnClickListener { choosePeriod() }
        }
        val selRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selRow.addView(subjectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f).apply { marginEnd = dp(8) })
        selRow.addView(periodBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(selRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        timerText = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            text = "00:00:00"
            setPadding(0, dp(20), 0, dp(12))
        }
        root.addView(timerText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        recBtn = MaterialButton(this).apply {
            textSize = 16f
            setOnClickListener { onRecClick() }
        }
        defaultRecTint = recBtn.backgroundTintList
        pauseBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 16f
            visibility = android.view.View.GONE
            setOnClickListener {
                if (RecordService.paused) RecordService.resume(this@MainActivity) else RecordService.pause(this@MainActivity)
                handler.postDelayed({ updateRecUi() }, 300)
            }
        }
        val recRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recRow.addView(pauseBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) })
        recRow.addView(recBtn, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(recRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val importBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "파일 불러오기"
            textSize = 14f
            setOnClickListener { pickLauncher.launch(arrayOf("audio/*", "video/mp4")) }
        }
        root.addView(importBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })

        // 목록 영역
        val listHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        listHead.addView(Ui.section(this, "녹음 목록").apply { setPadding(0, dp(28), 0, dp(6)) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        driveNote = Ui.sub(this, "").apply {
            setPadding(0, 0, 0, dp(6))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        listHead.addView(driveNote)
        root.addView(listHead, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui.divider(this))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        swipe = SwipeRefreshLayout(this)
        swipe.addView(scroll)
        swipe.setOnRefreshListener {
            if (DrivePrefs.connected(this)) SyncWorker.enqueue(this)
            handler.postDelayed({ swipe.isRefreshing = false; refreshList(true) }, 2500)
        }
        root.addView(swipe, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        askPermissions()
        handleShare(intent)
        Reminder.schedule(this)
        askBatteryOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** 녹음 알림의 "녹음 시작" 버튼으로 열렸을 때 */
    private fun handleStartFromReminder(i: Intent?) {
        if (i?.getBooleanExtra("start_record", false) != true) return
        i.removeExtra("start_record")
        getSystemService(android.app.NotificationManager::class.java).cancel(Notif.REMIND_ID)
        if (RecordService.currentBase == null) onRecClick()
    }

    /** 녹음이 꺼지지 않게 배터리 최적화 예외를 한 번 요청 */
    private fun askBatteryOnce() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        if (sp.getBoolean("asked_battery", false)) return
        sp.edit().putBoolean("asked_battery", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("녹음이 끊기지 않게")
            .setMessage("휴대폰이 배터리를 아끼려고 녹음 중인 앱을 끌 수 있습니다. 다음 화면에서 \"허용\"을 눌러 주세요.")
            .setPositiveButton("설정하기") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun currentPeriod(): Int = manualPeriod ?: Store.periodOf(java.time.LocalDateTime.now())

    private fun periodLabel(p: Int) = if (p > 0) "${p}교시" else "교시 없음"

    private fun choosePeriod() {
        val labels = arrayOf("자동 (시작 시각 기준)") + (1..8).map { "${it}교시" }.toTypedArray() + arrayOf("교시 없음")
        val cur = when (manualPeriod) { null -> 0; 0 -> 9; else -> manualPeriod!! }
        MaterialAlertDialogBuilder(this)
            .setTitle("교시")
            .setSingleChoiceItems(labels, cur) { d, which ->
                manualPeriod = when (which) { 0 -> null; 9 -> 0; else -> which }
                updateRecUi()
                d.dismiss()
            }
            .show()
    }

    /** 다른 앱에서 "공유 → 강의녹음"으로 보낸 파일 */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val uris = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            else -> return
        }
        i.action = null
        if (uris.isNotEmpty()) importUris(uris)
    }

    private fun importUris(uris: List<Uri>) {
        if (subject.isBlank() || subject == "강의") {
            subject = Prefs.defaultSubject(this) ?: Prefs.subjects(this).firstOrNull() ?: "강의"
        }
        val subj = subject
        Toast.makeText(this, "${uris.size}개 불러오는 중, 과목: $subj", Toast.LENGTH_SHORT).show()
        Thread {
            var ok = 0
            var fail = ""
            for (u in uris) {
                try {
                    Importer.import(applicationContext, u, subj)
                    ok++
                } catch (e: Exception) {
                    fail = e.message ?: "오류"
                }
            }
            runOnUiThread {
                refreshList(true)
                val msg = if (fail.isEmpty()) "${ok}개 불러옴" else "${ok}개 불러옴, 실패: $fail"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 2, 0, "카메라").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 1, 1, "설정").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 2) {
            startActivity(Intent(this, CameraActivity::class.java))
            return true
        }
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        subject = Prefs.defaultSubject(this) ?: Prefs.subjects(this).firstOrNull() ?: "강의"
        subjectBtn.text = subject
        handler.post(ticker)
        Recovery.run(this)
        SyncWorker.enqueue(this)
        refreshList(true)
        handler.postDelayed(refresher, 3000)
        handleStartFromReminder(intent)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(refresher)
    }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun chooseSubject() {
        val subjects = Prefs.subjects(this).ifEmpty { listOf("강의") }
        val cur = subjects.indexOf(subject)
        MaterialAlertDialogBuilder(this)
            .setTitle("과목")
            .setSingleChoiceItems(subjects.toTypedArray(), cur) { d, which ->
                subject = subjects[which]
                subjectBtn.text = subject
                d.dismiss()
            }
            .show()
    }

    private fun onRecClick() {
        if (RecordService.currentBase != null) {
            RecordService.stop(this)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                askPermissions()
                return
            }
            RecordService.start(this, subject, currentPeriod())
            manualPeriod = null
        }
        handler.postDelayed({
            updateRecUi()
            refreshList(true)
        }, 500)
    }

    private fun updateRecUi() {
        val recording = RecordService.currentBase != null
        recBtn.text = if (recording) "정지" else "녹음 시작"
        pauseBtn.visibility = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        pauseBtn.text = if (RecordService.paused) "계속" else "일시정지"
        recBtn.backgroundTintList =
            if (recording) ColorStateList.valueOf(Color.parseColor("#C0392B")) else defaultRecTint
        subjectBtn.isEnabled = !recording
        periodBtn.isEnabled = !recording
        periodBtn.text = periodLabel(currentPeriod()) + if (manualPeriod == null && !recording) " (자동)" else ""
        if (recording) {
            val s = RecordService.elapsedMs() / 1000
            timerText.text = "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        } else {
            timerText.text = "00:00:00"
        }
    }

    private fun statusLabel(s: String) = when (s) {
        "recording" -> "녹음 중"
        "queued" -> "전사 대기"
        "transcribing" -> "전사 중"
        "waiting" -> "재시도 대기"
        "done" -> "완료"
        "failed" -> "실패"
        else -> "녹음됨"
    }

    private fun statusColor(s: String): Int? = when (s) {
        "done" -> Color.parseColor("#4E8A5C")
        "failed" -> Color.parseColor("#C0392B")
        "recording", "transcribing", "queued", "waiting" -> Color.parseColor("#B8862B")
        else -> null
    }

    private fun refreshList(force: Boolean) {
        val bases = Store.list(this)
        val states = bases.map { Store.readState(this, it) }
        val drive = DrivePrefs.connected(this)
        val dlabels = bases.map { if (drive) driveLabel(it) else "" }
        driveNote.text = when {
            !drive -> ""
            DrivePrefs.needsReconnect(this) -> "드라이브 다시 연결 필요"
            DrivePrefs.lastSync(this).isNotEmpty() -> "동기화 ${DrivePrefs.lastSync(this)}"
            else -> ""
        }
        driveNote.setTextColor(
            if (DrivePrefs.needsReconnect(this)) Color.parseColor("#C0392B")
            else Ui.color(driveNote, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        val sig = bases.zip(states).joinToString("|") { "${it.first}:${it.second.status}:${it.second.msg}" } + dlabels.joinToString()
        if (!force && sig == lastListSignature) return
        lastListSignature = sig

        listBox.removeAllViews()
        if (bases.isEmpty()) {
            listBox.addView(Ui.sub(this, "아직 녹음이 없습니다.").apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        for ((i, pair) in bases.zip(states).withIndex()) {
            val (base, st) = pair
            val dl = dlabels[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.body(this, base))
            val statusLine = statusLabel(st.status) + (if (st.msg.isNotEmpty()) ", ${st.msg}" else "") +
                (if (dl.isNotEmpty()) ", $dl" else "")
            texts.addView(Ui.sub(this, statusLine).apply {
                val col = statusColor(st.status)
                if (col != null) post { setTextColor(col) }
            })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            if (st.status != "recording") {
                val more = TextView(this).apply {
                    text = "⋮"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                }
                more.setOnClickListener { showMenu(more, base, st.status) }
                row.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)))
                row.setOnClickListener {
                    startActivity(Intent(this, DetailActivity::class.java).putExtra("base", base))
                }
            }
            listBox.addView(row)
            listBox.addView(Ui.divider(this))
        }
    }

    private fun driveLabel(base: String): String {
        val r = SyncStore.get(this, base)
        val audio = Store.audio(this, base).exists()
        val txt = Store.txt(this, base)
        return when {
            !audio && r.audioId.isNotEmpty() && txt.exists() && txt.lastModified() == r.txtSyncedLocalMtime -> "드라이브 ✓"
            !audio && r.audioId.isNotEmpty() -> "드라이브에만 있음"
            audio && r.audioId.isEmpty() -> "드라이브 올리기 대기"
            txt.exists() && txt.lastModified() != r.txtSyncedLocalMtime -> "드라이브 올리기 대기"
            r.audioId.isNotEmpty() -> "드라이브 ✓"
            else -> ""
        }
    }

    private fun showMenu(anchor: TextView, base: String, status: String) {
        val pm = PopupMenu(this, anchor)
        val hasAudio = Store.audio(this, base).exists() || Store.src(this, base).exists() ||
            (DrivePrefs.connected(this) && SyncStore.get(this, base).audioId.isNotEmpty())
        pm.menu.add(0, 1, 0, "녹음 공유").isEnabled = hasAudio
        val hasTxt = Store.txt(this, base).exists()
        pm.menu.add(0, 2, 1, "전사본 공유 (파일)").isEnabled = hasTxt
        pm.menu.add(0, 5, 1, "전사본 복사").isEnabled = hasTxt
        val st = Store.readState(this, base)
        pm.menu.add(0, 6, 2, "누락 구간 다시 확인").isEnabled = status == "done" && hasAudio
        pm.menu.add(0, 3, 2, "처음부터 다시 전사").isEnabled = hasAudio
        pm.menu.add(0, 7, 3, "제목 수정")
        pm.menu.add(0, 4, 3, "삭제")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> shareAudio(base)
                // text/plain으로 보내면 카톡 등이 파일이 아닌 "글"로 받으려다 실패함 → 일반 파일로 보냄
                2 -> share(Store.txt(this, base), "application/octet-stream")
                5 -> copyText(Store.txt(this, base))
                3 -> retry(base, status)
                6 -> {
                    Store.writeState(this, base, "queued", "누락 구간 다시 확인 대기")
                    TranscribeWorker.enqueue(this, base, redo = true)
                    refreshList(true)
                }
                4 -> confirmDelete(base)
                7 -> renameDialog(base, status)
            }
            true
        }
        pm.show()
    }

    private fun share(f: File, mime: String) {
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, uri)
        startActivity(Intent.createChooser(i, f.name))
    }

    private fun shareAudio(base: String) {
        val f = Store.audio(this, base)
        if (f.exists()) {
            share(f, "audio/mp4")
            return
        }
        Toast.makeText(this, "드라이브에서 녹음 내려받는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try { DriveNow.fetchAudio(applicationContext, base) } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) share(f, "audio/mp4")
                else Toast.makeText(this, "녹음을 내려받지 못했습니다", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun copyText(f: File) {
        if (!f.exists()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(f.name, f.readText()))
        Toast.makeText(this, "전사본을 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun retry(base: String, status: String) {
        if (Store.src(this, base).exists()) {
            // 불러온 파일 변환이 멈춘 경우: 변환부터 다시
            Store.audio(this, base).delete()
            Store.amp(this, base).delete()
            Store.writeState(this, base, "queued", "변환 다시 시작")
            TranscribeWorker.enqueue(this, base)
            refreshList(true)
            return
        }
        if (status == "done") {
            MaterialAlertDialogBuilder(this)
                .setMessage("전사본을 지우고 처음부터 다시 전사할까요? 직접 수정한 내용도 사라집니다.")
                .setPositiveButton("다시 전사") { _, _ ->
                    Store.clearTranscript(this, base)
                    Store.writeState(this, base, "queued", "인터넷 연결되면 시작")
                    TranscribeWorker.enqueue(this, base)
                    refreshList(true)
                }
                .setNegativeButton("취소", null)
                .show()
        } else {
            // 이미 끝난 조각은 그대로 두고 이어서 진행
            Store.writeState(this, base, "queued", "인터넷 연결되면 시작")
            TranscribeWorker.enqueue(this, base)
            refreshList(true)
        }
    }

    private fun renameDialog(base: String, status: String) {
        if (status in setOf("recording", "queued", "transcribing", "waiting")) {
            Toast.makeText(this, "녹음이나 전사가 끝난 뒤에 바꿀 수 있습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val til = com.google.android.material.textfield.TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply { hint = "제목" }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            setText(base)
            setSelection(base.length)
        }
        til.addView(et)
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(til, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("제목 수정")
            .setView(wrap)
            .setPositiveButton("저장") { _, _ ->
                val to = (et.text?.toString() ?: "").replace(Regex("""[\\/*?"<>|\n]"""), "").trim()
                if (to.isEmpty() || to == base) return@setPositiveButton
                if (Store.exists(this, to)) {
                    Toast.makeText(this, "같은 제목이 이미 있습니다", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Store.rename(this, base, to)
                SyncStore.move(this, base, to)
                val txt = Store.txt(this, to)
                if (txt.exists()) {
                    val lines = txt.readLines().toMutableList()
                    if (lines.isNotEmpty() && lines[0] == "# $base") {
                        lines[0] = "# $to"
                        txt.writeText(lines.joinToString("\n") + "\n")
                    }
                }
                refreshList(true)
                if (DrivePrefs.connected(this)) {
                    Thread {
                        try {
                            DriveNow.renameRemote(applicationContext, to)
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "드라이브 이름은 못 바꿨습니다. 다음 동기화 때 다시 시도합니다.", Toast.LENGTH_LONG).show()
                            }
                        }
                        SyncWorker.enqueue(applicationContext)
                    }.start()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(base: String) {
        val r = SyncStore.get(this, base)
        val onDrive = DrivePrefs.connected(this) && (r.audioId.isNotEmpty() || r.txtId.isNotEmpty())
        val items = if (onDrive) arrayOf("이 기기에서만 삭제", "드라이브에서도 삭제") else arrayOf("삭제")
        MaterialAlertDialogBuilder(this)
            .setTitle(base)
            .setItems(items) { _, which ->
                TranscribeWorker.cancel(this, base)
                if (onDrive && which == 1) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage("드라이브의 녹음과 전사본까지 지웁니다. 다른 기기에서도 사라집니다.")
                        .setPositiveButton("삭제") { _, _ ->
                            Thread {
                                val ok = try { DriveNow.deleteRemote(applicationContext, base); true } catch (e: Exception) { false }
                                runOnUiThread {
                                    if (ok) {
                                        Store.delete(this, base)
                                        SyncStore.remove(this, base)
                                        SyncStore.ignore(this, base)
                                    } else {
                                        Toast.makeText(this, "드라이브에서 지우지 못했습니다. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_LONG).show()
                                    }
                                    refreshList(true)
                                }
                            }.start()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                } else {
                    Store.delete(this, base)
                    if (onDrive) SyncStore.ignore(this, base) else SyncStore.remove(this, base)
                    refreshList(true)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
