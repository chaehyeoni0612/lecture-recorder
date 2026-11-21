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
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
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
    private var camera: Camera? = null
    private lateinit var focusBox: View
    private lateinit var zoomText: TextView
    private lateinit var copyBtn: TextView
    private lateinit var thumb: ImageView
    private var lastClip: File? = null
    private var copyOn = true

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

        focusBox = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            alpha = 0f
        }
        root.addView(focusBox, FrameLayout.LayoutParams(dp(64), dp(64)))

        zoomText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            text = "1.0x"
        }
        root.addView(zoomText, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(128)
        })

        copyOn = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("cam_copy", true)
        copyBtn = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            setOnClickListener {
                copyOn = !copyOn
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("cam_copy", copyOn).apply()
                updateCopyBtn()
            }
        }
        updateCopyBtn()
        root.addView(copyBtn, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.DKGRAY)
            setOnClickListener { startActivity(android.content.Intent(this@CameraActivity, PhotosActivity::class.java)) }
            setOnLongClickListener {
                lastClip?.let {
                    copyToClipboard(it)
                    Toast.makeText(this@CameraActivity, "클립보드에 복사함", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        root.addView(thumb, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(50)
            marginStart = dp(28)
        })

        val scanBtn = TextView(this).apply {
            text = "스캔"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener { startScan() }
        }
        root.addView(scanBtn, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(50)
            marginEnd = dp(28)
        })

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
        setupGestures()

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
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, ic)
                camera!!.cameraInfo.zoomState.observe(this) { z -> zoomText.text = "%.1fx".format(z.zoomRatio) }
                capture = ic
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열 수 없습니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun fileName(): String = Photos.newName(this)

    private val scanLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val res = com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@registerForActivityResult
        val pages = res.pages ?: return@registerForActivityResult
        Thread {
            val base = Photos.newName(this, "_스캔")
            var last: Uri? = null
            for ((i, pg) in pages.withIndex()) {
                val name = if (pages.size > 1) "${base}${i + 1}" else base
                last = Photos.saveFrom(this, pg.imageUri, name) ?: last
            }
            runOnUiThread {
                count += pages.size
                countText.text = "${count}장 저장"
                last?.let { afterSaved(it) }
            }
        }.start()
    }

    private fun startScan() {
        val opts = com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        com.google.mlkit.vision.documentscanner.GmsDocumentScanning.getClient(opts)
            .getStartScanIntent(this)
            .addOnSuccessListener { scanLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { Toast.makeText(this, "스캐너를 열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show() }
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
                r.savedUri?.let { afterSaved(it) }
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun updateCopyBtn() {
        copyBtn.text = if (copyOn) "복사 켬" else "복사 끔"
        copyBtn.alpha = if (copyOn) 1f else 0.6f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val cur = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                cam.cameraControl.setZoomRatio(cur * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                camera?.cameraControl?.setZoomRatio(1f)
                return true
            }
        })
        preview.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) tap.onTouchEvent(e)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
        val half = dp(32)
        focusBox.translationX = x - half
        focusBox.translationY = y - half
        focusBox.animate().cancel()
        focusBox.alpha = 1f
        focusBox.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
    }

    /** 저장된 사진을 미리보기에 띄우고, 켜져 있으면 클립보드에도 복사 */
    private fun afterSaved(uri: Uri) {
        Thread {
            try {
                val dir = File(cacheDir, "clip").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                val o = BitmapFactory.Options().apply { inSampleSize = 16 }
                val bmp = BitmapFactory.decodeFile(f.path, o)
                runOnUiThread {
                    lastClip = f
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = View.VISIBLE
                    if (copyOn) copyToClipboard(f)
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun copyToClipboard(f: File) {
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newUri(contentResolver, "사진", u))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event?.repeatCount == 0) take()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
