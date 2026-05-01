package com.example.pdfedi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.pdfedi.database.StudyNote
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var isEditMode = false
    var isDrawingEnabled = false
    var pageIndex = -1
    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f
    var pageRenderScale = 2.5f

    var isEraserObject = false
    var isEraserPixel = false
    var isHighlighter = false
    var isCommentTool = false
    var isTextBoxTool = false
    var isSignatureTool = false
    var isLineLockEnabled = false
    var eraserTargetMode = EraseTargetMode.ALL

    var searchMatches: List<SearchHit> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedSearchHitId: Long? = null
        set(value) {
            field = value
            invalidate()
        }

    var textHighlightProvider: ((PdfPoint, PdfPoint) -> List<PdfQuad>)? = null
    var signatureTemplateProvider: (() -> SignatureTemplate?)? = null
    var onSessionChanged: ((Set<Int>) -> Unit)? = null
    var onCommentRequested: ((pdfX: Float, pdfY: Float) -> Unit)? = null
    var onNoteTapped: ((StudyNote) -> Unit)? = null
    var onTextBoxRequested: ((pdfX: Float, pdfY: Float) -> Unit)? = null
    var onTextBoxTapped: ((TextBoxAnnotation) -> Unit)? = null

    private var pdfX0 = 0f
    private var pdfY0 = 0f
    private var pdfWidth = 0f
    private var pdfHeight = 0f

    private val touchTolerance = 2f
    private var previousX = 0f
    private var previousY = 0f
    private var currentPreviewPoints = mutableListOf<PdfPoint>()
    private var selectionStart: PdfPoint? = null
    private var previewStrokeWidth = 0f

    private var draggingTextBox: TextBoxAnnotation? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging = false
    private var touchDownX = 0f
    private var touchDownY = 0f

    private val commentIconDrawable by lazy {
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_comment)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setPdfBounds(x0: Float, y0: Float, width: Float, height: Float) {
        pdfX0 = x0
        pdfY0 = y0
        pdfWidth = width
        pdfHeight = height
        invalidate()
    }

    private fun getPdfToViewMatrix(): Matrix {
        val matrix = Matrix()
        if (pdfWidth == 0f || pdfHeight == 0f || width == 0 || height == 0) return matrix

        val bitmapWidth = pdfWidth * pageRenderScale
        val bitmapHeight = pdfHeight * pageRenderScale
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val scale = min(viewWidth / bitmapWidth, viewHeight / bitmapHeight)
        val dx = (viewWidth - bitmapWidth * scale) / 2f
        val dy = (viewHeight - bitmapHeight * scale) / 2f

        matrix.postTranslate(-pdfX0, -pdfY0)
        matrix.postScale(pageRenderScale * scale, pageRenderScale * scale)
        matrix.postTranslate(dx, dy)
        return matrix
    }

    private fun createStrokePaint(color: Int, width: Float, highlighter: Boolean): Paint {
        val paint = Paint().apply {
            this.color = color
            strokeWidth = width
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        if (highlighter) {
            paint.color = ColorUtils.setAlphaComponent(color, 96)
            paint.strokeWidth = width * 2.4f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                paint.blendMode = BlendMode.MULTIPLY
            } else {
                @Suppress("DEPRECATION")
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
        return paint
    }

    private fun createSearchPaint(selected: Boolean): Paint {
        return Paint().apply {
            color = if (selected) Color.parseColor("#FFF176") else Color.parseColor("#80DEEA")
            style = Paint.Style.FILL
            alpha = if (selected) 170 else 110
            isAntiAlias = true
        }
    }

    private fun createSearchOutlinePaint(selected: Boolean): Paint {
        return Paint().apply {
            color = if (selected) Color.parseColor("#F57F17") else Color.parseColor("#00838F")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
    }

    private fun createTextBoxFillPaint(color: Int): Paint {
        return Paint().apply {
            this.color = ColorUtils.setAlphaComponent(color, 26)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    private fun createTextBoxBorderPaint(color: Int): Paint {
        return Paint().apply {
            this.color = ColorUtils.setAlphaComponent(color, 190)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
    }

    private fun buildPath(points: List<PdfPoint>, lineLocked: Boolean = false): Path {
        val path = Path()
        if (points.isEmpty()) return path

        path.moveTo(points.first().x, points.first().y)
        if (points.size == 1) {
            path.lineTo(points.first().x, points.first().y)
            return path
        }

        if (lineLocked) {
            path.lineTo(points.last().x, points.last().y)
            return path
        }

        var previous = points.first()
        for (index in 1 until points.size) {
            val point = points[index]
            val midX = (previous.x + point.x) / 2f
            val midY = (previous.y + point.y) / 2f
            path.quadTo(previous.x, previous.y, midX, midY)
            previous = point
        }
        path.lineTo(previous.x, previous.y)
        return path
    }

    private fun drawSearchMatches(canvas: Canvas) {
        searchMatches.forEach { hit ->
            val selected = hit.id == selectedSearchHitId
            val fillPaint = createSearchPaint(selected)
            val outlinePaint = createSearchOutlinePaint(selected)
            hit.quads.forEach { quad ->
                val rect = quad.toRect()
                val rectF = RectF(rect.left, rect.top, rect.right, rect.bottom)
                canvas.drawRoundRect(rectF, 3f, 3f, fillPaint)
                canvas.drawRoundRect(rectF, 3f, 3f, outlinePaint)
            }
        }
    }

    private fun drawPageAnnotations(canvas: Canvas) {
        StrokeManager.highlightsForPage(pageIndex).forEach { highlight ->
            val fillPaint = Paint().apply {
                color = ColorUtils.setAlphaComponent(highlight.color, (highlight.opacity * 255f).toInt().coerceIn(0, 255))
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            highlight.quads.forEach { quad ->
                val rect = quad.toRect()
                canvas.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.bottom), 2f, 2f, fillPaint)
            }
        }

        StrokeManager.inkForPage(pageIndex).forEach { stroke ->
            val paint = createStrokePaint(stroke.color, stroke.width, stroke.isHighlighter)
            canvas.drawPath(buildPath(stroke.points), paint)
        }

        if (currentPreviewPoints.isNotEmpty()) {
            val previewPaint = createStrokePaint(currentDrawColor, previewStrokeWidth, isHighlighter)
            val previewPath = buildPath(
                currentPreviewPoints,
                lineLocked = isHighlighter && isLineLockEnabled
            )
            canvas.drawPath(previewPath, previewPaint)
        }

        // --- NEW: Wrapped Text Box Rendering ---
        StrokeManager.textBoxesForPage(pageIndex).forEach { textBox ->
            val rect = RectF(textBox.rect.left, textBox.rect.top, textBox.rect.right, textBox.rect.bottom)
            canvas.drawRoundRect(rect, 8f, 8f, createTextBoxFillPaint(textBox.color))
            canvas.drawRoundRect(rect, 8f, 8f, createTextBoxBorderPaint(textBox.color))

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textBox.color
                textSize = textBox.fontSize
            }

            val padding = 12f
            val textWidth = (textBox.rect.right - textBox.rect.left - (padding * 2)).coerceAtLeast(50f)

            val staticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(textBox.text, 0, textBox.text.length, textPaint, textWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(textBox.text, textPaint, textWidth.toInt(), Layout.Alignment.ALIGN_NORMAL, 1.2f, 0f, false)
            }

            canvas.save()
            canvas.translate(textBox.rect.left + padding, textBox.rect.top + padding)
            staticLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawCommentIcons(canvas: Canvas) {
        val mapMatrix = getPdfToViewMatrix()
        commentIconDrawable?.let { icon ->
            StrokeManager.notesForPage(pageIndex).forEach { note ->
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

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pdfWidth == 0f) return

        canvas.save()
        canvas.concat(getPdfToViewMatrix())
        drawSearchMatches(canvas)
        drawPageAnnotations(canvas)
        canvas.restore()

        drawCommentIcons(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageIndex == -1 || pdfWidth == 0f) return false

        if (event.pointerCount > 1) {
            cancelCurrentInteraction()
            parent.requestDisallowInterceptTouchEvent(false)
            return false
        }

        val mapMatrix = getPdfToViewMatrix()
        val inverse = Matrix()
        mapMatrix.invert(inverse)
        val pts = floatArrayOf(event.x, event.y)
        inverse.mapPoints(pts)
        val pdfX = pts[0]
        val pdfY = pts[1]

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchDownX = event.x
            touchDownY = event.y
        }

        val tappedNote = StrokeManager.notesForPage(pageIndex).find { note ->
            val notePts = floatArrayOf(note.x, note.y)
            mapMatrix.mapPoints(notePts)
            distanceSquared(notePts[0], notePts[1] - 40f, event.x, event.y) < 80f * 80f
        }
        if (tappedNote != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP && !isDragging) {
                onNoteTapped?.invoke(tappedNote)
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val tappedTextBox = StrokeManager.textBoxesForPage(pageIndex).find { box ->
                box.rect.expandedBy(12f).contains(pdfX, pdfY)
            }
            if (tappedTextBox != null && isEditMode && (isTextBoxTool || !isDrawingEnabled)) {
                draggingTextBox = tappedTextBox
                dragOffsetX = pdfX - tappedTextBox.rect.left
                dragOffsetY = pdfY - tappedTextBox.rect.top
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        if (draggingTextBox != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - touchDownX)
                    val dy = abs(event.y - touchDownY)
                    if (dx > 5f || dy > 5f) {
                        isDragging = true
                    }

                    val newLeft = pdfX - dragOffsetX
                    val newTop = pdfY - dragOffsetY
                    val width = draggingTextBox!!.rect.right - draggingTextBox!!.rect.left
                    val height = draggingTextBox!!.rect.bottom - draggingTextBox!!.rect.top

                    draggingTextBox = draggingTextBox!!.copy(
                        rect = PdfRect(newLeft, newTop, newLeft + width, newTop + height)
                    )

                    StrokeManager.updateTextBox(draggingTextBox!!)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) {
                        onTextBoxTapped?.invoke(draggingTextBox!!)
                    } else {
                        val changedPages = StrokeManager.updateTextBox(draggingTextBox!!)
                        if (changedPages.isNotEmpty()) {
                            onSessionChanged?.invoke(changedPages)
                        }
                    }
                    draggingTextBox = null
                    isDragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        val canInteract = isDrawingEnabled ||
                isEraserObject ||
                isEraserPixel ||
                isCommentTool ||
                isTextBoxTool ||
                isSignatureTool

        if (!canInteract) return false

        if (isCommentTool) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                onCommentRequested?.invoke(pdfX, pdfY)
            }
            return true
        }

        if (isTextBoxTool) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                onTextBoxRequested?.invoke(pdfX, pdfY)
            }
            return true
        }

        if (isSignatureTool) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val template = signatureTemplateProvider?.invoke()
                if (template != null) {
                    val strokes = StrokeManager.buildSignatureStrokes(
                        pageIndex = pageIndex,
                        centerX = pdfX,
                        centerY = pdfY,
                        desiredWidth = 168f,
                        color = currentDrawColor,
                        template = template
                    )
                    var changedPages = emptySet<Int>()
                    StrokeManager.beginContinuousMutation()
                    strokes.forEach { stroke ->
                        changedPages = changedPages + StrokeManager.addInkStroke(stroke)
                    }
                    changedPages = changedPages + StrokeManager.finishContinuousMutation()
                    if (changedPages.isNotEmpty()) {
                        onSessionChanged?.invoke(changedPages)
                        invalidate()
                    }
                }
            }
            return true
        }

        val pdfScale = mapMatrix.mapRadius(1f)
        if (isEraserObject || isEraserPixel) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        StrokeManager.beginContinuousMutation()
                    }
                    parent.requestDisallowInterceptTouchEvent(true)

                    val tolerancePx = if (isEraserPixel) 12f else 35f

                    val changedPages = StrokeManager.eraseAt(
                        pageIndex = pageIndex,
                        x = pdfX,
                        y = pdfY,
                        tolerance = tolerancePx / max(pdfScale, 0.001f),
                        isPartial = isEraserPixel,
                        targetMode = eraserTargetMode
                    )
                    if (changedPages.isNotEmpty()) {
                        onSessionChanged?.invoke(changedPages)
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val changedPages = StrokeManager.finishContinuousMutation()
                    if (changedPages.isNotEmpty()) {
                        onSessionChanged?.invoke(changedPages)
                    }
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val pressureScale = if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                    (0.65f + event.getPressure(0).coerceIn(0.25f, 2f) * 0.55f)
                } else {
                    1f
                }

                previewStrokeWidth = (currentStrokeWidth / max(pdfScale, 0.001f)) * pressureScale
                currentPreviewPoints.clear()
                val point = PdfPoint(pdfX, pdfY)
                currentPreviewPoints.add(point)
                selectionStart = point
                previousX = pdfX
                previousY = pdfY
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(pdfX - previousX)
                val dy = abs(pdfY - previousY)
                val pdfTolerance = touchTolerance / max(pdfScale, 0.001f)

                if (dx >= pdfTolerance || dy >= pdfTolerance) {
                    currentPreviewPoints.add(PdfPoint(pdfX, pdfY))
                    previousX = pdfX
                    previousY = pdfY
                }
            }

            MotionEvent.ACTION_UP -> {
                currentPreviewPoints.add(PdfPoint(pdfX, pdfY))

                val changedPages = if (isHighlighter) {
                    commitHighlightStroke(pdfX, pdfY)
                } else {
                    commitInkStroke()
                }

                if (changedPages.isNotEmpty()) {
                    onSessionChanged?.invoke(changedPages)
                }
                parent.requestDisallowInterceptTouchEvent(false)
                cancelCurrentInteraction()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                cancelCurrentInteraction()
            }
        }

        invalidate()
        return true
    }

    private fun commitInkStroke(): Set<Int> {
        if (currentPreviewPoints.isEmpty()) return emptySet()
        return StrokeManager.addInkStroke(
            InkStroke(
                pageIndex = pageIndex,
                points = currentPreviewPoints.toList(),
                color = currentDrawColor,
                width = previewStrokeWidth
            )
        )
    }

    private fun commitHighlightStroke(endX: Float, endY: Float): Set<Int> {
        val start = selectionStart ?: return emptySet()
        val end = PdfPoint(endX, endY)
        val snappedQuads = textHighlightProvider?.invoke(start, end).orEmpty()

        if (snappedQuads.isNotEmpty()) {
            return StrokeManager.addTextHighlight(
                TextHighlight(
                    pageIndex = pageIndex,
                    quads = snappedQuads,
                    color = currentDrawColor
                )
            )
        }

        val points = if (isLineLockEnabled) listOf(start, end) else currentPreviewPoints.toList()
        return StrokeManager.addInkStroke(
            InkStroke(
                pageIndex = pageIndex,
                points = points,
                color = currentDrawColor,
                width = previewStrokeWidth,
                isHighlighter = true
            )
        )
    }

    private fun cancelCurrentInteraction() {
        currentPreviewPoints.clear()
        selectionStart = null
        invalidate()
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }
}