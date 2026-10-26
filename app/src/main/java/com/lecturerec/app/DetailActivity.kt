package com.lecturerec.app

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 녹음 하나: 위에는 재생기, 아래에는 전사본 보기·수정 */
class DetailActivity : AppCompatActivity() {

    private lateinit var base: String
    private var player: MediaPlayer? = null
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: MaterialButton
    private lateinit var editor: EditText
    private var dirty = false
    private var userSeeking = false
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 250)
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = intent.getStringExtra("base") ?: run { finish(); return }
        title = base
        DriveNow.openBase = base
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // ── 재생기 ─────────────────────────
        seek = SeekBar(this)
        root.addView(seek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        timeText = Ui.sub(this, "00:00 / 00:00").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(timeText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun outlined(label: String, onClick: () -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 14f
                setOnClickListener { onClick() }
            }
        ctrl.addView(outlined("−10초") { skip(-10_000) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        playBtn = MaterialButton(this).apply {
            text = "재생"
            textSize = 14f
            setOnClickListener { togglePlay() }
        }
        ctrl.addView(playBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        ctrl.addView(outlined("+10초") { skip(10_000) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(ctrl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // ── 전사본 ─────────────────────────
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.section(this, "전사본"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val jump = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "커서 줄부터 재생"
            textSize = 12f
            setAllCaps(false)
            setOnClickListener { playFromCursorLine() }
        }
        head.addView(jump)
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui.divider(this))

        editor = EditText(this).apply {
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            setHorizontallyScrolling(false)
            background = null
            setPadding(0, dp(8), 0, dp(8))
        }
        val txt = Store.txt(this, base)
        if (txt.exists()) {
            editor.setText(txt.readText())
        } else {
            editor.setText("")
            editor.hint = "아직 전사본이 없습니다."
            editor.isEnabled = false
            jump.isEnabled = false
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { dirty = true }
        })
        dirty = false
        root.addView(editor, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        setupPlayer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun setupPlayer() {
        val f = Store.audio(this, base)
        if (!f.exists()) {
            playBtn.isEnabled = false
            if (DrivePrefs.connected(this) && SyncStore.get(this, base).audioId.isNotEmpty()) {
                timeText.text = "드라이브에서 녹음 내려받는 중…"
                Thread {
                    val ok = try { DriveNow.fetchAudio(applicationContext, base) } catch (e: Exception) { false }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (ok) {
                            playBtn.isEnabled = true
                            setupPlayer()
                        } else {
                            timeText.text = "녹음을 내려받지 못했습니다. 인터넷이나 드라이브 연결을 확인해 주세요."
                        }
                    }
                }.start()
            } else {
                timeText.text = "녹음 파일이 아직 준비되지 않았습니다"
            }
            return
        }
        val p = MediaPlayer()
        try {
            p.setDataSource(f.absolutePath)
            p.prepare()
        } catch (e: Exception) {
            p.release()
            playBtn.isEnabled = false
            timeText.text = "녹음 파일을 열 수 없습니다"
            return
        }
        p.setOnCompletionListener { playBtn.text = "재생" }
        player = p
        seek.max = p.duration
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = "${fmt(v)} / ${fmt(p.duration)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                player?.seekTo(seek.progress)
            }
        })
        updateTime()
    }

    private fun updateTime() {
        val p = player ?: return
        if (!userSeeking) {
            seek.progress = p.currentPosition
            timeText.text = "${fmt(p.currentPosition)} / ${fmt(p.duration)}"
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playBtn.text = "재생"
        } else {
            p.start()
            playBtn.text = "일시정지"
        }
    }

    private fun skip(ms: Int) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration))
        updateTime()
    }

    /** 커서가 있는 줄의 [mm:ss]로 이동해서 재생 */
    private fun playFromCursorLine() {
        val p = player ?: return
        val text = editor.text.toString()
        val pos = editor.selectionStart.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        val line = text.substring(start, end)
        val m = Regex("""\[(\d{1,3}):(\d{2})]""").find(line)
        if (m == null) {
            Toast.makeText(this, "이 줄에는 시간이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val ms = (m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()) * 1000
        p.seekTo(ms.coerceIn(0, p.duration))
        if (!p.isPlaying) {
            p.start()
            playBtn.text = "일시정지"
        }
        updateTime()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "저장").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            save()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        leave()
        return true
    }

    private fun save() {
        if (!editor.isEnabled) return
        Store.txt(this, base).writeText(editor.text.toString())
        dirty = false
        SyncWorker.enqueue(this)
        Toast.makeText(this, "저장했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun leave() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage("수정한 내용을 저장할까요?")
            .setPositiveButton("저장") { _, _ -> save(); finish() }
            .setNegativeButton("저장 안 함") { _, _ -> finish() }
            .setNeutralButton("취소", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        player?.let {
            if (it.isPlaying) {
                it.pause()
                playBtn.text = "재생"
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        if (DriveNow.openBase == base) DriveNow.openBase = null
        if (::base.isInitialized) {
            val b = base
            val app = applicationContext
            Thread { DriveNow.cleanOne(app, b) }.start()
        }
        super.onDestroy()
    }
}
