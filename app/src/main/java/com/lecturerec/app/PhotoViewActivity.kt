package com.lecturerec.app

import android.app.RecoverableSecurityException
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 사진 크게 보기: 확대, 옆으로 넘기기, 복사, 공유, 삭제 */
class PhotoViewActivity : AppCompatActivity() {
    private lateinit var img: ZoomImageView
    private lateinit var nameText: TextView
    private var list = listOf<Photos.Photo>()
    private var index = 0
    private var filter: String? = null

    private val deleteReq = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) afterDelete()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        filter = intent.getStringExtra("filter")
        index = intent.getIntExtra("index", 0)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        img = ZoomImageView(this)
        img.onSwipe = { d -> show(index + d) }
        root.addView(img, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        top.addView(btn("닫기") { finish() })
        nameText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        top.addView(nameText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(0, 0, 0, dp(16))
        }
        for (b in bottomButtons()) bottom.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        list = Photos.list(this).filter { filter == null || SyncStore.subjectOf(it.name) == filter }
        show(index)
    }

    /** 편집 버튼 등을 넣으려면 여기서 */
    private fun bottomButtons(): List<TextView> = listOf(
        btn("복사") { copy() },
        btn("공유") { share() },
        btn("삭제") { delete() }
    )

    private fun btn(t: String, f: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { f() }
    }

    private fun cur() = list.getOrNull(index)

    private fun show(i: Int) {
        if (list.isEmpty()) {
            finish()
            return
        }
        if (i < 0 || i >= list.size) return
        index = i
        val p = list[i]
        nameText.text = "${p.name}  (${i + 1}/${list.size})"
        Thread {
            val b = try { Photos.load(this, p.uri, 2400) } catch (e: Exception) { null }
            runOnUiThread { if (index == i) img.setBitmap(b) }
        }.start()
    }

    private fun copy() {
        val p = cur() ?: return
        Thread {
            try {
                val f = Photos.copyToClipboardFile(this, p.uri)
                runOnUiThread {
                    Photos.setClip(this, f)
                    Toast.makeText(this, "클립보드에 복사함", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun share() {
        val p = cur() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, p.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "사진 공유"))
    }

    private fun delete() {
        val p = cur() ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(p.uri))
            deleteReq.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage("이 사진을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                try {
                    contentResolver.delete(p.uri, null, null)
                    afterDelete()
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                        deleteReq.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun afterDelete() {
        list = list.filterIndexed { i, _ -> i != index }
        show(index.coerceAtMost(list.size - 1))
    }
}
