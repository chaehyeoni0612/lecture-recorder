package com.lecturerec.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 셔터 소리 없이 찍는 카메라. 갤러리의 "강의녹음" 앨범에 저장. 볼륨 버튼으로도 찍힘. */
class CameraActivity : AppCompatActivity() {

    private var capture: ImageCapture? = null
    private lateinit var preview: PreviewView
    private lateinit var flash: View
    private lateinit var countText: TextView
    private var count = 0

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        flash = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        root.addView(flash, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val back = TextView(this).apply {
            text = "닫기"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))

        countText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(countText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        val shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(4), Color.parseColor("#888888"))
            }
            setOnClickListener { take() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(76), dp(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            perm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, ic)
                capture = ic
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열 수 없습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fileName(): String {
        val now = LocalDateTime.now()
        val rec = RecordService.currentBase
        val subject = (if (rec != null) SyncStore.subjectOf(rec) else null)
            ?: Prefs.defaultSubject(this) ?: "강의"
        val p = Store.periodOf(now)
        return subject.replace(Regex("""[\\/:*?"<>|]"""), "") + "_" +
            now.format(DateTimeFormatter.ofPattern("yyMMdd")) + "_" +
            (if (p > 0) "${p}교시_" else "") +
            now.format(DateTimeFormatter.ofPattern("HHmmss"))
    }

    private fun take() {
        val ic = capture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/강의녹음")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        // 찍혔다는 표시: 화면을 잠깐 어둡게 (소리 없음)
        flash.alpha = 0.6f
        flash.animate().alpha(0f).setDuration(200).start()
        ic.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                count++
                countText.text = "${count}장 저장"
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) take()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
