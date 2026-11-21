package com.lecturerec.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/** 두 손가락 확대, 끌어서 이동, 두 번 눌러 원래대로. 확대 안 했을 때 옆으로 밀면 onSwipe */
@SuppressLint("ClickableViewAccessibility")
class ZoomImageView(c: Context) : AppCompatImageView(c) {
    var onSwipe: ((Int) -> Unit)? = null
    private val m = Matrix()
    private var base = 1f
    private var scale = 1f

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setBitmap(b: Bitmap?) {
        setImageBitmap(b)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0) return
        base = minOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        scale = 1f
        m.reset()
        m.postScale(base, base)
        m.postTranslate((width - d.intrinsicWidth * base) / 2, (height - d.intrinsicHeight * base) / 2)
        imageMatrix = m
    }

    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2 - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2 - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
        imageMatrix = m
    }

    private val sg = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(1f, 8f)
            val f = ns / scale
            scale = ns
            m.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
    })

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1.01f) {
                m.postTranslate(-dx, -dy)
                clamp()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale <= 1.01f && e1 != null && abs(vx) > abs(vy) && abs(e2.x - e1.x) > width / 6) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) fit() else {
                scale = 2.5f
                m.postScale(2.5f, 2.5f, e.x, e.y)
                clamp()
            }
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        if (!sg.isInProgress) gd.onTouchEvent(e)
        return true
    }
}
