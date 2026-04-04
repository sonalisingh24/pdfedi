package com.example.pdfedi

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.pdfedi.database.StudyNote

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isDrawingEnabled = false
    var pageIndex = -1

    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f
    var isEraser = false
    var isHighlighter = false

    // Add this property at the top of CustomDrawView.kt
    var currentNotes: List<StudyNote> = emptyList()
    var onNoteInteraction: ((x: Float, y: Float, clickedNote: StudyNote?) -> Unit)? = null
    var isNoteTool = false

    private var currentStroke: Stroke? = null
    private var currentPaint = createPaint()

    // NEW: Variables for Bezier Curve Smoothing
    private var previousX = 0f
    private var previousY = 0f
    private val touchTolerance = 2f

    init {
        // Highlighters with BlendModes require Software Layer on some Android versions to work correctly on top of Bitmaps
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun createPaint(
        color: Int = currentDrawColor,
        width: Float = currentStrokeWidth,
        highlighter: Boolean = isHighlighter
    ): Paint {
        val paint = Paint().apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        // NEW: True Highlighting Logic
        if (highlighter) {
            // Make the color semi-transparent
            paint.color = ColorUtils.setAlphaComponent(color, 100)

            // Multiply Blend Mode ensures text beneath stays black
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                paint.blendMode = BlendMode.MULTIPLY
            } else {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            paint.strokeWidth = width * 2.5f
        }
        return paint
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)


        val myStrokes = StrokeManager.globalStrokes.filter { it.pageIndex == pageIndex }
        for (stroke in myStrokes) {
            val paint = createPaint(stroke.color, stroke.width, stroke.isHighlighter)
            canvas.drawPath(stroke.path, paint)
        }

        currentStroke?.let {
            canvas.drawPath(it.path, currentPaint)
        }

        val iconSize = 60f
        val paintNote = Paint().apply {
            color = Color.parseColor("#FFEB3B") // Yellow sticky color
            style = Paint.Style.FILL
            setShadowLayer(4f, 2f, 2f, Color.parseColor("#40000000"))
        }
        val paintNoteOutline = Paint().apply {
            color = Color.parseColor("#FBC02D")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        for (note in currentNotes) {
            val rect = RectF(note.x - iconSize/2, note.y - iconSize/2, note.x + iconSize/2, note.y + iconSize/2)
            canvas.drawRect(rect, paintNote)
            canvas.drawRect(rect, paintNoteOutline)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled || pageIndex == -1) return false

        // NEW: Multi-touch / Palm Rejection.
        // If 2 or more fingers touch, abort drawing and let ZoomableRecyclerView zoom.
        if (event.pointerCount > 1) {
            currentStroke = null
            invalidate()
            return false
        }

        val touchX = event.x
        val touchY = event.y

        // NEW: Object Eraser Logic
        if (isEraser) {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Erase any stroke within a 40-pixel radius of the finger
                    val didErase = StrokeManager.eraseStrokesAt(touchX, touchY, pageIndex, 40f)
                    if (didErase) invalidate()
                }
            }
            return true
        }

        // --- Standard Drawing Logic (Pen & Highlighter) ---
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPaint = createPaint()
                currentStroke = Stroke(
                    pageIndex,
                    mutableListOf(),
                    currentDrawColor,
                    currentStrokeWidth,
                    false, // isEraser is false because we handle it above
                    isHighlighter,
                    this.width.toFloat(),
                    this.height.toFloat()
                )

                currentStroke?.path?.moveTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))

                previousX = touchX
                previousY = touchY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(touchX - previousX)
                val dy = Math.abs(touchY - previousY)

                // NEW: Bezier Curve Smoothing
                // Only register the point if the finger moved far enough (Touch Tolerance)
                if (dx >= touchTolerance || dy >= touchTolerance) {

                    // Create a smooth curve to the halfway point between previous and current touch
                    val midX = (previousX + touchX) / 2
                    val midY = (previousY + touchY) / 2
                    currentStroke?.path?.quadTo(previousX, previousY, midX, midY)

                    currentStroke?.points?.add(PointF(touchX, touchY))
                    previousX = touchX
                    previousY = touchY
                }
            }
            MotionEvent.ACTION_UP -> {
                currentStroke?.path?.lineTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))

                // Palm Rejection: Accidental tap check
                val pointCount = currentStroke?.points?.size ?: 0
                if (pointCount > 2) {
                    currentStroke?.let { StrokeManager.addStroke(it) }
                }

                currentStroke = null
            }
            else -> return false
        }

        if (isNoteTool || currentNotes.isNotEmpty()) {
            if (event.action == MotionEvent.ACTION_UP) {
                // 1. Check if we tapped an EXISTING note (allow opening notes even with Hand tool)
                val iconRadius = 40f
                var tappedNote: StudyNote? = null
                for (note in currentNotes) {
                    val dx = note.x - touchX
                    val dy = note.y - touchY
                    if (dx * dx + dy * dy <= iconRadius * iconRadius) {
                        tappedNote = note
                        break
                    }
                }

                if (tappedNote != null) {
                    onNoteInteraction?.invoke(touchX, touchY, tappedNote)
                    return true
                }
                // 2. If no existing note was tapped, and Note tool is active, create a new one
                else if (isNoteTool) {
                    onNoteInteraction?.invoke(touchX, touchY, null)
                    return true
                }
            }
            if (isNoteTool) return true // Consume touch so it doesn't pan the page
        }
        invalidate()
        return true
    }
}