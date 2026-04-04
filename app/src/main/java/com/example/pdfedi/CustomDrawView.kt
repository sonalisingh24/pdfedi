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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageIndex == -1) return false

        // SMART TOUCH: Is the user using a dedicated Stylus/Apple Pencil/S-Pen?
        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

        // SMART TOUCH: If 2 or more fingers are on screen, ABORT drawing instantly.
        if (event.pointerCount > 1) {
            currentStroke = null
            invalidate()
            // Tell the parent (ZoomableRecyclerView) to take over for scrolling/zooming
            parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        // If drawing is disabled AND we aren't using a stylus, don't do anything.
        if (!isDrawingEnabled && !isStylus && !isNoteTool && !isEraser) return false

        val touchX = event.x
        val touchY = event.y

        // Phase 4: Note Tool Logic
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

        // Phase 3: Object Eraser
        if (isEraser) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent.requestDisallowInterceptTouchEvent(true) // Lock scrolling while erasing
                    val didErase = StrokeManager.eraseStrokesAt(touchX, touchY, pageIndex, 40f)
                    if (didErase) invalidate()
                }
            }
            return true
        }

        // Standard Drawing Logic
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Lock the parent so the screen doesn't jiggle while writing
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
                // A second finger touched the screen mid-stroke! Abort the stroke and start zooming.
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
}