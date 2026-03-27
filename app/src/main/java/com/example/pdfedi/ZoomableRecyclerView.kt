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
                if (isDrawingMode) return false // STRICT MODE: No zooming while drawing

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
                if (isDrawingMode) return false // STRICT MODE: No zooming while drawing

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
                if (isDrawingMode) return false // STRICT MODE: Screen is totally frozen

                // If drawing is OFF and zoomed in, allow 1-finger panning (perfect for mouse)
                if (scaleFactor > 1.0f) {
                    translateX -= distanceX
                    translateY -= distanceY
                    fixBounds()
                    invalidate()
                    return true
                }
                return false
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
    // THE SHIELD: Stops the RecyclerView from stealing touches from the drawing canvas
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (isDrawingMode) {
            return false
        }
        return super.onInterceptTouchEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        val inverseEvent = MotionEvent.obtain(ev)

        // THE FIX: Use a true Android Matrix to translate the entire touch history perfectly
        val matrix = Matrix()
        matrix.postTranslate(-translateX, -translateY)
        matrix.postScale(1f / scaleFactor, 1f / scaleFactor)
        inverseEvent.transform(matrix)

        val handled = super.dispatchTouchEvent(inverseEvent)
        inverseEvent.recycle()

        return handled
    }
}