package com.example.pdfedi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isDrawingEnabled = false

    // Current Tool Settings
    var currentDrawColor = Color.parseColor("#F44336") // Default Red
    var currentStrokeWidth = 8f // Default Medium
    var isEraser = false
    var isHighlighter = false

    // Data class to remember exactly how each line was drawn
    private data class Stroke(val path: Path, val paint: Paint)
    private val strokes = mutableListOf<Stroke>()

    private var currentPath = Path()
    private var currentPaint = createPaint()

    init {
        // REQUIRED FOR ERASER: Forces the view to use software layers so CLEAR mode works properly
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    // Generates a fresh Paint brush based on your selected tools
    private fun createPaint(): Paint {
        val paint = Paint().apply {
            color = currentDrawColor
            strokeWidth = currentStrokeWidth
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        if (isEraser) {
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            paint.strokeWidth = currentStrokeWidth * 3f // Make eraser wider
        } else if (isHighlighter) {
            paint.alpha = 100 // 40% transparency so you can read text underneath
            paint.strokeWidth = currentStrokeWidth * 2.5f // Highlighters are wide
        }
        return paint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw history
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, stroke.paint)
        }
        // Draw current moving line
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled) return false

        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPaint = createPaint() // Grab the latest color/tool settings
                currentPath.moveTo(touchX, touchY)
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(touchX, touchY)
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(touchX, touchY)
                strokes.add(Stroke(currentPath, currentPaint))
                currentPath = Path() // Reset for next line
            }
            else -> return false
        }
        invalidate()
        return true
    }
}