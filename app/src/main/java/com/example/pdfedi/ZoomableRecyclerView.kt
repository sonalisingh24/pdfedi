package com.example.pdfedi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

class ZoomableRecyclerView(context: Context, attrs: AttributeSet?) : RecyclerView(context, attrs) {

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    private val minScale = 1.0f
    private val maxScale = 4.0f

    var isDrawingMode = false

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val previousScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(minScale, Math.min(scaleFactor, maxScale))

                val focusX = detector.focusX
                val focusY = detector.focusY
                translateX += (focusX - translateX) * (1f - scaleFactor / previousScale)
                translateY += (focusY - translateY) * (1f - scaleFactor / previousScale)

                fixBounds()
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isDrawingMode) return false // Prevent accidental zoom while drawing

                if (scaleFactor > minScale) {
                    scaleFactor = minScale
                    translateX = 0f
                    translateY = 0f
                } else {
                    scaleFactor = 2.5f
                    translateX = (width / 2f) - (e.x * scaleFactor)
                    translateY = (height / 2f) - (e.y * scaleFactor)
                }
                fixBounds()
                invalidate()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isDrawingMode && e2.pointerCount == 1) return false

                translateX -= distanceX
                translateY -= distanceY
                fixBounds()
                invalidate()
                return true
            }
        })
    }

    private fun fixBounds() {
        val maxTranslateX = width * (scaleFactor - 1f)
        val maxTranslateY = height * (scaleFactor - 1f)

        translateX = Math.max(-maxTranslateX, Math.min(0f, translateX))
        translateY = Math.max(-maxTranslateY, Math.min(0f, translateY))
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // CRUCIAL: Re-enable RecyclerView interception as soon as a second finger touches
        if (ev.pointerCount > 1) {
            requestDisallowInterceptTouchEvent(false)
        }

        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        val inverseEvent = MotionEvent.obtain(ev)
        val matrix = Matrix()
        matrix.postTranslate(-translateX, -translateY)
        matrix.postScale(1f / scaleFactor, 1f / scaleFactor)
        inverseEvent.transform(matrix)

        val handled = super.dispatchTouchEvent(inverseEvent)
        inverseEvent.recycle()

        return handled
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        try {
            // Always allow RecyclerView to intercept if there are 2+ fingers (enables scrolling)
            if (e.pointerCount > 1) {
                return super.onInterceptTouchEvent(e)
            }

            // Allow 1-finger touches to pass through directly to the CustomDrawView
            if (isDrawingMode && e.pointerCount == 1) {
                return false
            }

            return super.onInterceptTouchEvent(e)
        } catch (ex: IllegalArgumentException) {
            return false // Failsafe for rare ScaleGestureDetector pointer bugs
        }
    }
}