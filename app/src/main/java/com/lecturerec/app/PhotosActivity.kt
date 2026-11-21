package com.lecturerec.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** "강의녹음" 앨범 사진만 격자로 보기 */
class PhotosActivity : AppCompatActivity() {
    private lateinit var grid: GridLayout
    private lateinit var chips: LinearLayout
    private lateinit var empty: TextView
    private var filter: String? = null
    private var photos = listOf<Photos.Photo>()
    private val thumbs = HashMap<String, Bitmap>()

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "사진"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        empty = Ui.sub(this, "아직 찍은 사진이 없습니다.").apply { setPadding(dp(16), dp(16), dp(16), 0) }
        root.addView(empty)
        val cols = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        grid = GridLayout(this).apply { columnCount = cols }
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun subjectOf(name: String) = SyncStore.subjectOf(name)

    private fun reload() {
        Thread {
            val all = try { Photos.list(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                photos = all
                drawChips()
                drawGrid()
            }
        }.start()
    }

    private fun drawChips() {
        chips.removeAllViews()
        val subjects = photos.map { subjectOf(it.name) }.distinct()
        if (filter != null && filter !in subjects) filter = null
        for (s in listOf<String?>(null) + subjects) {
            val on = s == filter
            chips.addView(TextView(this).apply {
                text = s ?: "전체"
                textSize = 14f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                if (on) setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.color(chips, if (on) com.google.android.material.R.attr.colorOnSurface
                    else com.google.android.material.R.attr.colorOnSurfaceVariant))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(if (on) 2 else 1), Ui.color(chips,
                        if (on) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOutlineVariant))
                }
                setOnClickListener {
                    filter = s
                    drawChips()
                    drawGrid()
                }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun shown() = photos.filter { filter == null || subjectOf(it.name) == filter }

    private fun drawGrid() {
        grid.removeAllViews()
        val list = shown()
        empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        val size = (resources.displayMetrics.widthPixels - dp(2) * grid.columnCount) / grid.columnCount
        for ((i, p) in list.withIndex()) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#33888888"))
                setOnClickListener {
                    startActivity(Intent(this@PhotosActivity, PhotoViewActivity::class.java)
                        .putExtra("filter", filter).putExtra("index", i))
                }
            }
            grid.addView(iv, GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(1), dp(1), dp(1), dp(1))
            })
            val key = p.uri.toString()
            val cached = thumbs[key]
            if (cached != null) iv.setImageBitmap(cached) else pool.execute {
                val b = try { Photos.load(this, p.uri, size) } catch (e: Exception) { null }
                if (b != null) runOnUiThread {
                    thumbs[key] = b
                    iv.setImageBitmap(b)
                }
            }
        }
    }

    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
}
