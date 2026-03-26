package com.example.pdfedi

import android.content.Context
import android.graphics.Canvas
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

    // NEW: We will flip this switch from MainActivity when you click the Pen button!
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
                // THE FIX: Allow 1-finger panning in ALL directions if zoomed in.
                // If the Pen is ON, we force the user to use 2 fingers to pan so they can still draw!
                val isTwoFingers = e2.pointerCount > 1

                if (scaleFactor > 1.0f && (!isDrawingMode || isTwoFingers)) {
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

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)

        val inverseEvent = MotionEvent.obtain(ev)
        inverseEvent.setLocation(
            (ev.x - translateX) / scaleFactor,
            (ev.y - translateY) / scaleFactor
        )

        val handled = super.dispatchTouchEvent(inverseEvent)
        inverseEvent.recycle()

        // THE FIX: Block the native vertical list scroll if we are panning around a zoomed page
        val isTwoFingers = ev.pointerCount > 1
        if (scaleFactor > 1.0f && (!isDrawingMode || isTwoFingers) && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            return true
        }

        return handled
    }
    // This calculates the exact edges of the page and stops panning from passing them
    private fun fixBounds() {
        val maxTranslateX = width * (scaleFactor - 1f)
        val maxTranslateY = height * (scaleFactor - 1f)

        translateX = Math.max(-maxTranslateX, Math.min(0f, translateX))
        translateY = Math.max(-maxTranslateY, Math.min(0f, translateY))
    }
}