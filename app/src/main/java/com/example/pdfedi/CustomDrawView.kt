package com.example.pdfedi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.pdfedi.database.StudyNote
import androidx.core.graphics.toColorInt

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isDrawingEnabled = false
    var pageIndex = -1

    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f
    var isEraser = false
    var isHighlighter = false

    // Sticky Note Properties
    var currentNotes: List<StudyNote> = emptyList()
    var onNoteInteraction: ((x: Float, y: Float, clickedNote: StudyNote?) -> Unit)? = null
    var isNoteTool = false

    // NEW: Text Highlighter Properties
    var isTextHighlighter = false
    var pageTextLines: List<RectF> = emptyList()
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var currentSelectionRects = mutableListOf<RectF>()

    private var currentStroke: Stroke? = null
    private var currentPaint = createPaint()

    private var previousX = 0f
    private var previousY = 0f
    private val touchTolerance = 2f

    private val textSelectionColor = Color.parseColor("#4D33B5E5")

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun createPaint(
        color: Int = currentDrawColor,
        width: Float = currentStrokeWidth,
        highlighter: Boolean = isHighlighter || isTextHighlighter
    ): Paint {
        val paint = Paint().apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        if (highlighter) {
            paint.color = ColorUtils.setAlphaComponent(color, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                paint.blendMode = BlendMode.MULTIPLY
            } else {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
                if (isTextHighlighter || paint.style == Paint.Style.FILL) {
                paint.style = Paint.Style.FILL
            } else {
                paint.strokeWidth = width * 2.5f
            }
        }
        return paint
    }


    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Saved Strokes and Highlights
        val myStrokes = StrokeManager.globalStrokes.filter { it.pageIndex == pageIndex }
        for (stroke in myStrokes) {
            val paint = createPaint(stroke.color, stroke.width, stroke.isHighlighter)
            if (stroke.isTextHighlight && stroke.rects != null) {
                paint.style = Paint.Style.FILL
                for (rect in stroke.rects) {
                    canvas.drawRect(rect, paint)
                }
            } else {
                canvas.drawPath(stroke.path, paint)
            }
        }

        // Draw Freehand Active Stroke
        currentStroke?.let {
            canvas.drawPath(it.path, currentPaint)
        }

        if (isTextHighlighter && currentSelectionRects.isNotEmpty()) {
            val paint = Paint().apply {
                color = textSelectionColor
                style = Paint.Style.FILL
            }
            for (rect in currentSelectionRects) {
                canvas.drawRect(rect, paint)
            }
        }

        // Draw Sticky Notes
        val iconSize = 60f
        val paintNote = Paint().apply {
            color = "#FFEB3B".toColorInt()
            style = Paint.Style.FILL
            setShadowLayer(4f, 2f, 2f, "#40000000".toColorInt())
        }
        val paintNoteOutline = Paint().apply {
            color = "#FBC02D".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        for (note in currentNotes) {
            val rect = RectF(
                note.x - iconSize / 2,
                note.y - iconSize / 2,
                note.x + iconSize / 2,
                note.y + iconSize / 2
            )
            canvas.drawRect(rect, paintNote)
            canvas.drawRect(rect, paintNoteOutline)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageIndex == -1) return false

        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

        if (event.pointerCount > 1) {
            currentStroke = null
            currentSelectionRects.clear()
            invalidate()
            parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        if (!isDrawingEnabled && !isStylus && !isNoteTool && !isEraser && !isTextHighlighter) return false

        val touchX = event.x
        val touchY = event.y

        // Note Tool Logic
        if (isNoteTool || currentNotes.isNotEmpty()) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
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
                } else if (isNoteTool) {
                    onNoteInteraction?.invoke(touchX, touchY, null)
                    return true
                }
            }
            if (isNoteTool) return true
        }

        // Object Eraser Logic
        if (isEraser) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val didErase = StrokeManager.eraseStrokesAt(touchX, touchY, pageIndex, 40f)
                    if (didErase) invalidate()
                }
            }
            return true
        }

        // NEW: Text Highlighter Logic
        if (isTextHighlighter) {
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    startTouchX = touchX
                    startTouchY = touchY
                    currentSelectionRects.clear()
                }

                MotionEvent.ACTION_MOVE -> {
                    val padding = 25f
                    val left = Math.min(startTouchX, touchX) - padding
                    val right = Math.max(startTouchX, touchX) + padding
                    val top = Math.min(startTouchY, touchY) - padding
                    val bottom = Math.max(startTouchY, touchY) + padding
                    val selectionBox = RectF(left, top, right, bottom)

                    currentSelectionRects.clear()
                    for (line in pageTextLines) {
                        if (RectF.intersects(selectionBox, line)) {
                            currentSelectionRects.add(line)
                        }
                    }
                    invalidate()
                }

                MotionEvent.ACTION_UP -> {
                    invalidate()
                }
            }
            return true
        }

        // Standard Freehand Drawing Logic
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                currentPaint = createPaint()
                currentStroke = Stroke(
                    pageIndex,
                    mutableListOf(),
                    currentDrawColor,
                    currentStrokeWidth,
                    false,
                    isHighlighter,
                    this.width.toFloat(),
                    this.height.toFloat()
                )
                currentStroke?.path?.moveTo(touchX, touchY)
                currentStroke?.points?.add(PointF(touchX, touchY))
                previousX = touchX
                previousY = touchY
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                currentStroke = null
                invalidate()
                parent.requestDisallowInterceptTouchEvent(false)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(touchX - previousX)
                val dy = Math.abs(touchY - previousY)
                if (dx >= touchTolerance || dy >= touchTolerance) {
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

    fun hasSelection(): Boolean {
        return currentSelectionRects.isNotEmpty()
    }

    fun applyHighlightToSelection(color: Int) {
        if (currentSelectionRects.isNotEmpty()) {
            val newStroke = Stroke(
                pageIndex = pageIndex,
                points = mutableListOf(),
                color = color,
                width = currentStrokeWidth,
                isEraser = false,
                isHighlighter = true,
                canvasWidth = width.toFloat(),
                canvasHeight = height.toFloat(),
                path = Path(),
                rects = currentSelectionRects.toList(),
                isTextHighlight = true
            )
            StrokeManager.addStroke(newStroke)
            currentSelectionRects.clear() // Clear the blue selection box
            invalidate()
        }
    }

    fun clearSelection() {
        currentSelectionRects.clear()
        invalidate()
    }
}