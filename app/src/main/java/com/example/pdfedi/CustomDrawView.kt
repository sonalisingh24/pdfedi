package com.example.pdfedi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isDrawingEnabled = false
    var pageIndex = -1 // The Adapter will tell us which page this is

    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f
    var isEraser = false
    var isHighlighter = false

    private var currentStroke: Stroke? = null
    private var currentPaint = createPaint()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun createPaint(color: Int = currentDrawColor, width: Float = currentStrokeWidth, eraser: Boolean = isEraser, highlighter: Boolean = isHighlighter): Paint {
        val paint = Paint().apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }
        if (eraser) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            paint.strokeWidth = width * 3f
        } else if (highlighter) {
            paint.alpha = 100
            paint.strokeWidth = width * 2.5f
        }
        return paint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Global History for this specific page
        val myStrokes = StrokeManager.globalStrokes.filter { it.pageIndex == pageIndex }
        for (stroke in myStrokes) {
            val paint = createPaint(stroke.color, stroke.width, stroke.isEraser, stroke.isHighlighter)
            canvas.drawPath(stroke.path, paint)
        }

        // 2. Draw the line currently being drawn
        currentStroke?.let {
            canvas.drawPath(it.path, currentPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled || pageIndex == -1) return false

        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPaint = createPaint()

                // THE FIX: Grab the exact pixel width and height of this specific view
                currentStroke = Stroke(
                    pageIndex,
                    mutableListOf(),
                    currentDrawColor,
                    currentStrokeWidth,
                    isEraser,
                    isHighlighter,
                    this.width.toFloat(),   // Passing the True Width
                    this.height.toFloat()   // Passing the True Height
                )

                currentStroke?.path?.moveTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))
            }
            MotionEvent.ACTION_MOVE -> {
                currentStroke?.path?.lineTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))
            }
            MotionEvent.ACTION_UP -> {
                currentStroke?.path?.lineTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))

                // THE FIX: Palm Rejection.
                // A real drawing has dozens of points. An accidental tap only has 1 or 2.
                val pointCount = currentStroke?.points?.size ?: 0
                if (pointCount > 2) {
                    currentStroke?.let { StrokeManager.addStroke(it) }
                }

                currentStroke = null
            }
            else -> return false
        }
        invalidate()
        return true
    }
}