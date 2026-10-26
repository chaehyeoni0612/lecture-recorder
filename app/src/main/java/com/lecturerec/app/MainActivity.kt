package com.lecturerec.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var subjectSpinner: Spinner
    private lateinit var timerText: TextView
    private lateinit var recBtn: Button
    private lateinit var listBox: LinearLayout
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

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.ensureChannels(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val subjRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        subjRow.addView(TextView(this).apply { text = "과목  "; textSize = 16f })
        subjectSpinner = Spinner(this)
        subjRow.addView(subjectSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(subjRow)

        timerText = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.MONOSPACE
            text = "00:00:00"
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(timerText)

        recBtn = Button(this).apply { textSize = 18f }
        recBtn.setOnClickListener { onRecClick() }
        root.addView(recBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))

        val settingsBtn = Button(this).apply { text = "설정" }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        root.addView(settingsBtn)

        root.addView(TextView(this).apply {
            text = "녹음 목록"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        loadSubjects()
        handler.post(ticker)
        refreshList(true)
        handler.postDelayed(refresher, 3000)
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

    private fun loadSubjects() {
        val subjects = Prefs.subjects(this).ifEmpty { listOf("강의") }
        subjectSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, subjects)
        val def = Prefs.defaultSubject(this)
        val idx = if (def != null) subjects.indexOf(def) else -1
        if (idx >= 0) subjectSpinner.setSelection(idx)
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
            val subject = subjectSpinner.selectedItem?.toString() ?: "강의"
            RecordService.start(this, subject)
        }
        handler.postDelayed({
            updateRecUi()
            refreshList(true)
        }, 500)
    }

    private fun updateRecUi() {
        val recording = RecordService.currentBase != null
        recBtn.text = if (recording) "녹음 정지" else "녹음 시작"
        subjectSpinner.isEnabled = !recording
        if (recording) {
            val s = (System.currentTimeMillis() - RecordService.startedAt) / 1000
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

    private fun refreshList(force: Boolean) {
        val bases = Store.list(this)
        val states = bases.map { Store.readState(this, it) }
        val sig = bases.zip(states).joinToString("|") { "${it.first}:${it.second.status}:${it.second.msg}" }
        if (!force && sig == lastListSignature) return
        lastListSignature = sig

        listBox.removeAllViews()
        if (bases.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "아직 녹음이 없습니다." })
            return
        }
        for ((base, st) in bases.zip(states)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = base
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            row.addView(TextView(this).apply {
                text = statusLabel(st.status) + if (st.msg.isNotEmpty()) " · ${st.msg}" else ""
                textSize = 13f
            })

            if (st.status != "recording") {
                val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun add(label: String, enabled: Boolean = true, action: () -> Unit) {
                    val b = Button(this).apply {
                        text = label
                        textSize = 12f
                        isEnabled = enabled
                        setOnClickListener { action() }
                    }
                    btns.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                add("녹음 공유") { share(Store.audio(this, base), "audio/mp4") }
                add("전사본 공유", Store.txt(this, base).exists()) { share(Store.txt(this, base), "text/plain") }
                add("다시 전사") { retry(base, st.status) }
                add("삭제") { confirmDelete(base) }
                row.addView(btns)
            }
            listBox.addView(row)
        }
    }

    private fun share(f: File, mime: String) {
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "드라이브에 올리기"))
    }

    private fun retry(base: String, status: String) {
        if (status == "done") {
            AlertDialog.Builder(this)
                .setMessage("전사본을 지우고 처음부터 다시 전사할까요?")
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

    private fun confirmDelete(base: String) {
        AlertDialog.Builder(this)
            .setMessage("$base\n녹음과 전사본을 이 폰에서 삭제할까요? (드라이브에 올린 파일은 그대로 남습니다)")
            .setPositiveButton("삭제") { _, _ ->
                TranscribeWorker.cancel(this, base)
                Store.delete(this, base)
                refreshList(true)
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
