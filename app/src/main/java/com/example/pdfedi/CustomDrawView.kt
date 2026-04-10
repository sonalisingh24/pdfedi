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

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isDrawingEnabled = false
    var pageIndex = -1

    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f

    var isEraserObject = false
    var isEraserPixel = false
    var isHighlighter = false

    var isCommentTool = false
    var activeNotes: List<StudyNote> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var onEmptySpaceTapped: ((pdfX: Float, pdfY: Float) -> Unit)? = null
    var onNoteTapped: ((StudyNote) -> Unit)? = null

    var onEraseCompleted: (() -> Unit)? = null

    // PDF Space Variables
    private var pdfX0 = 0f
    private var pdfY0 = 0f
    private var pdfWidth = 0f
    private var pdfHeight = 0f

    private var currentStroke: Stroke? = null
    private var previousX = 0f
    private var previousY = 0f
    private val touchTolerance = 2f

    private val commentIconDrawable by lazy {
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_comment)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setPdfBounds(x0: Float, y0: Float, w: Float, h: Float) {
        pdfX0 = x0
        pdfY0 = y0
        pdfWidth = w
        pdfHeight = h
        invalidate()
    }

    private fun getPdfToViewMatrix(): Matrix {
        val matrix = Matrix()
        if (pdfWidth == 0f || pdfHeight == 0f || width == 0) return matrix

        val bitmapWidth = pdfWidth * 2.5f
        val bitmapHeight = pdfHeight * 2.5f
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Mimic ImageView's fitCenter exactly
        val scale = Math.min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val dx = (viewWidth - bitmapWidth * scale) / 2f
        val dy = (viewHeight - bitmapHeight * scale) / 2f

        // Map PDF document directly onto the View's drawn area
        matrix.postTranslate(-pdfX0, -pdfY0)
        matrix.postScale(2.5f * scale, 2.5f * scale)
        matrix.postTranslate(dx, dy)

        return matrix
    }

    private fun createPaint(color: Int, width: Float, highlighter: Boolean): Paint {
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
            paint.strokeWidth = width * 2.5f
        }
        return paint
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pdfWidth == 0f) return

        // 1. Draw Strokes inside the PDF coordinate matrix
        canvas.save()
        canvas.concat(getPdfToViewMatrix())

        val myStrokes = StrokeManager.globalStrokes.filter { it.pageIndex == pageIndex }
        for (stroke in myStrokes) {
            val paint = createPaint(stroke.color, stroke.width, stroke.isHighlighter)

            if (stroke.isEraser) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    paint.blendMode = BlendMode.CLEAR
                } else {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
            canvas.drawPath(stroke.path, paint)
        }

        currentStroke?.let {
            val paint = createPaint(it.color, it.width, it.isHighlighter)
            if (it.isEraser) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    paint.blendMode = BlendMode.CLEAR
                } else {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
            canvas.drawPath(it.path, paint)
        }

        canvas.restore()

        val mapMatrix = getPdfToViewMatrix()

        commentIconDrawable?.let { icon ->
            activeNotes.forEach { note ->
                val pts = floatArrayOf(note.x, note.y)
                mapMatrix.mapPoints(pts)

                val iconSize = 80
                val left = (pts[0] - (iconSize / 2f)).toInt()
                val bottom = pts[1].toInt()
                val top = bottom - iconSize
                val right = left + iconSize

                icon.setBounds(left, top, right, bottom)
                icon.draw(canvas)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageIndex == -1 || pdfWidth == 0f) return false

        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

        if (event.pointerCount > 1) {
            currentStroke = null
            invalidate()
            parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        val mapMatrix = getPdfToViewMatrix()

        val tappedNote = activeNotes.find { note ->
            val notePts = floatArrayOf(note.x, note.y)
            mapMatrix.mapPoints(notePts)

            Math.hypot((notePts[0] - event.x).toDouble(), ((notePts[1] - 40f) - event.y).toDouble()) < 80f
        }

        if (tappedNote != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                onNoteTapped?.invoke(tappedNote)
            }
            return true
        }

        if (!isDrawingEnabled && !isStylus && !isEraserObject && !isEraserPixel && !isCommentTool) return false

        val inverse = Matrix()
        mapMatrix.invert(inverse)
        val pts = floatArrayOf(event.x, event.y)
        inverse.mapPoints(pts)
        val pdfX = pts[0]
        val pdfY = pts[1]

        if (isCommentTool) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                onEmptySpaceTapped?.invoke(pdfX, pdfY)
            }
            return true
        }

        val pdfScale = mapMatrix.mapRadius(1f)

        if (isEraserObject) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    val didErase = StrokeManager.eraseStrokesAt(pdfX, pdfY, pageIndex, 40f / pdfScale)
                    if (didErase) invalidate()
                }
                MotionEvent.ACTION_UP -> onEraseCompleted?.invoke()
                MotionEvent.ACTION_CANCEL -> { }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)

                val strokePdfWidth = currentStrokeWidth / pdfScale
                currentStroke = Stroke(
                    pageIndex, mutableListOf(), currentDrawColor, strokePdfWidth,
                    isEraserPixel, isHighlighter
                )

                currentStroke?.path?.moveTo(pdfX, pdfY)
                currentStroke?.points?.add(PointF(pdfX, pdfY))
                previousX = pdfX
                previousY = pdfY
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(pdfX - previousX)
                val dy = Math.abs(pdfY - previousY)
                val pdfTolerance = touchTolerance / pdfScale

                if (dx >= pdfTolerance || dy >= pdfTolerance) {
                    val midX = (previousX + pdfX) / 2
                    val midY = (previousY + pdfY) / 2
                    currentStroke?.path?.quadTo(previousX, previousY, midX, midY)
                    currentStroke?.points?.add(PointF(pdfX, pdfY))
                    previousX = pdfX
                    previousY = pdfY
                }
            }

            MotionEvent.ACTION_UP -> {
                currentStroke?.path?.lineTo(pdfX, pdfY)
                currentStroke?.points?.add(PointF(pdfX, pdfY))

                if ((currentStroke?.points?.size ?: 0) > 2) {
                    currentStroke?.let { StrokeManager.addStroke(it) }
                }
                currentStroke = null
                if (isEraserPixel) onEraseCompleted?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> {
                currentStroke = null
                invalidate()
            }
            else -> return false
        }

        invalidate()
        return true
    }
}