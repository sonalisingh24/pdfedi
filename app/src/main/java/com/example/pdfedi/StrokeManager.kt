package com.example.pdfedi

import com.example.pdfedi.database.StudyNote
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class NavigationLayoutMode { CONTINUOUS_VERTICAL, HORIZONTAL_PAGED }

enum class EraseTargetMode { ALL, INK_ONLY, HIGHLIGHTS_ONLY, TEXT_ONLY }

data class PdfPoint(val x: Float, val y: Float)

data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun expandedBy(amount: Float): PdfRect = PdfRect(
        left = left - amount,
        top = top - amount,
        right = right + amount,
        bottom = bottom + amount
    )
}

data class PdfQuad(
    val ulX: Float,
    val ulY: Float,
    val urX: Float,
    val urY: Float,
    val llX: Float,
    val llY: Float,
    val lrX: Float,
    val lrY: Float
) {
    fun toRect(): PdfRect {
        val left = min(min(ulX, urX), min(llX, lrX))
        val top = min(min(ulY, urY), min(llY, lrY))
        val right = max(max(ulX, urX), max(llX, lrX))
        val bottom = max(max(ulY, urY), max(llY, lrY))
        return PdfRect(left, top, right, bottom)
    }

    fun contains(x: Float, y: Float, tolerance: Float): Boolean = toRect().expandedBy(tolerance).contains(x, y)
}

sealed interface PageAnnotation {
    val id: Long
    val pageIndex: Int
}

data class InkStroke(
    override val id: Long = newAnnotationId(),
    override val pageIndex: Int,
    val points: List<PdfPoint>,
    val color: Int,
    val width: Float,
    val isHighlighter: Boolean = false,
    val isSignature: Boolean = false
) : PageAnnotation

data class TextHighlight(
    override val id: Long = newAnnotationId(),
    override val pageIndex: Int,
    val quads: List<PdfQuad>,
    val color: Int,
    val opacity: Float = 0.32f
) : PageAnnotation

data class TextBoxAnnotation(
    override val id: Long = newAnnotationId(),
    override val pageIndex: Int,
    val rect: PdfRect,
    val text: String,
    val color: Int,
    val fontSize: Float
) : PageAnnotation

data class ReaderBookmark(
    val id: Long = newAnnotationId(),
    val pageIndex: Int,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class SignatureTemplate(
    val strokes: List<List<PdfPoint>>,
    val aspectRatio: Float
)

data class SearchHit(
    val id: Long = newAnnotationId(),
    val pageIndex: Int,
    val quads: List<PdfQuad>
)

data class DocumentSessionState(
    val inkStrokes: List<InkStroke> = emptyList(),
    val textHighlights: List<TextHighlight> = emptyList(),
    val textBoxes: List<TextBoxAnnotation> = emptyList(),
    val notes: List<StudyNote> = emptyList(),
    val bookmarks: List<ReaderBookmark> = emptyList()
)

object StrokeManager {
    private var initialState = DocumentSessionState()
    private var currentState = DocumentSessionState()
    private val undoStack = mutableListOf<DocumentSessionState>()
    private val redoStack = mutableListOf<DocumentSessionState>()
    private var continuousMutationSnapshot: DocumentSessionState? = null

    val inkStrokes: List<InkStroke>
        get() = currentState.inkStrokes

    val textHighlights: List<TextHighlight>
        get() = currentState.textHighlights

    val textBoxes: List<TextBoxAnnotation>
        get() = currentState.textBoxes

    val notes: List<StudyNote>
        get() = currentState.notes

    val bookmarks: List<ReaderBookmark>
        get() = currentState.bookmarks

    fun beginDocument(
        strokes: List<InkStroke>,
        highlights: List<TextHighlight>,
        textBoxes: List<TextBoxAnnotation>,
        notes: List<StudyNote>,
        bookmarks: List<ReaderBookmark>
    ) {
        initialState = DocumentSessionState(
            inkStrokes = strokes.map(::normalizeStroke),
            textHighlights = highlights,
            textBoxes = textBoxes,
            notes = notes,
            bookmarks = bookmarks.sortedBy { it.pageIndex }
        )
        currentState = initialState
        undoStack.clear()
        redoStack.clear()
        continuousMutationSnapshot = null
    }

    fun sessionState(): DocumentSessionState = currentState

    fun markSaved() {
        initialState = currentState
        undoStack.clear()
        redoStack.clear()
        continuousMutationSnapshot = null
    }

    fun discardSession() {
        currentState = initialState
        undoStack.clear()
        redoStack.clear()
        continuousMutationSnapshot = null
    }

    fun clearDocument() {
        initialState = DocumentSessionState()
        currentState = DocumentSessionState()
        undoStack.clear()
        redoStack.clear()
        continuousMutationSnapshot = null
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun isDirty(): Boolean = currentState != initialState

    fun notesForPage(pageIndex: Int): List<StudyNote> = currentState.notes.filter { it.pageIndex == pageIndex }

    fun inkForPage(pageIndex: Int): List<InkStroke> = currentState.inkStrokes.filter { it.pageIndex == pageIndex }

    fun highlightsForPage(pageIndex: Int): List<TextHighlight> = currentState.textHighlights.filter { it.pageIndex == pageIndex }

    fun textBoxesForPage(pageIndex: Int): List<TextBoxAnnotation> = currentState.textBoxes.filter { it.pageIndex == pageIndex }

    fun addInkStroke(stroke: InkStroke): Set<Int> = mutate(setOf(stroke.pageIndex)) {
        copy(inkStrokes = inkStrokes + normalizeStroke(stroke))
    }

    fun addTextHighlight(highlight: TextHighlight): Set<Int> = mutate(setOf(highlight.pageIndex)) {
        copy(textHighlights = textHighlights + highlight)
    }

    fun addTextBox(textBox: TextBoxAnnotation): Set<Int> = mutate(setOf(textBox.pageIndex)) {
        copy(textBoxes = textBoxes + textBox)
    }

    fun updateTextBox(textBox: TextBoxAnnotation): Set<Int> = mutate(setOf(textBox.pageIndex)) {
        copy(textBoxes = textBoxes.map { existing -> if (existing.id == textBox.id) textBox else existing })
    }

    fun deleteTextBox(textBox: TextBoxAnnotation): Set<Int> = mutate(setOf(textBox.pageIndex)) {
        copy(textBoxes = textBoxes.filterNot { it.id == textBox.id })
    }

    fun upsertNote(note: StudyNote): Set<Int> = mutate(setOf(note.pageIndex)) {
        val withoutExisting = notes.filterNot { existing ->
            (existing.id != 0 && note.id != 0 && existing.id == note.id) ||
                (existing.pageIndex == note.pageIndex && existing.x == note.x && existing.y == note.y)
        }
        copy(notes = withoutExisting + note)
    }

    fun deleteNote(note: StudyNote): Set<Int> = mutate(setOf(note.pageIndex)) {
        copy(notes = notes.filterNot { existing ->
            if (note.id != 0) {
                existing.id == note.id
            } else {
                existing.pageIndex == note.pageIndex &&
                    existing.x == note.x &&
                    existing.y == note.y &&
                    existing.textContent == note.textContent
            }
        })
    }

    fun addBookmark(bookmark: ReaderBookmark): Set<Int> = mutate(setOf(bookmark.pageIndex)) {
        copy(bookmarks = (bookmarks + bookmark).sortedBy { it.pageIndex })
    }

    fun removeBookmark(bookmark: ReaderBookmark): Set<Int> = mutate(setOf(bookmark.pageIndex)) {
        copy(bookmarks = bookmarks.filterNot { it.id == bookmark.id })
    }

    fun clearBookmarks() {
        currentState = currentState.copy(bookmarks = emptyList())
    }

    fun clearPage(pageIndex: Int): Set<Int> = mutate(setOf(pageIndex)) {
        copy(
            inkStrokes = inkStrokes.filterNot { it.pageIndex == pageIndex },
            textHighlights = textHighlights.filterNot { it.pageIndex == pageIndex },
            textBoxes = textBoxes.filterNot { it.pageIndex == pageIndex },
            notes = notes.filterNot { it.pageIndex == pageIndex }
        )
    }

    fun eraseAt(
        pageIndex: Int,
        x: Float,
        y: Float,
        tolerance: Float,
        isPartial: Boolean,
        targetMode: EraseTargetMode
    ): Set<Int> {
        val before = currentState
        val updatedState = currentState.run {
            val updatedInk = if (isPartial) {
                eraseInkPartially(inkStrokes, pageIndex, x, y, tolerance, targetMode)
            } else {
                val r2 = tolerance * tolerance
                inkStrokes.filterNot { stroke ->
                    stroke.pageIndex == pageIndex &&
                            matchesEraseTarget(stroke, targetMode) &&
                            strokeIntersectsCircle(stroke, x, y, r2)
                }
            }

            val updatedHighlights = if (isPartial) {
                textHighlights
            } else {
                textHighlights.filterNot { highlight ->
                    highlight.pageIndex == pageIndex &&
                            targetMode.allowsHighlights() &&
                            highlight.quads.any { it.contains(x, y, tolerance) }
                }
            }

            val updatedTextBoxes = if (isPartial) {
                textBoxes
            } else {
                textBoxes.filterNot { textBox ->
                    textBox.pageIndex == pageIndex &&
                            targetMode.allowsText() &&
                            textBox.rect.expandedBy(tolerance).contains(x, y)
                }
            }

            copy(
                inkStrokes = updatedInk,
                textHighlights = updatedHighlights,
                textBoxes = updatedTextBoxes
            )
        }

        return applyStateChange(before, updatedState, setOf(pageIndex))
    }

    fun beginContinuousMutation() {
        if (continuousMutationSnapshot == null) {
            continuousMutationSnapshot = currentState
        }
    }

    fun finishContinuousMutation(): Set<Int> {
        val snapshot = continuousMutationSnapshot ?: return emptySet()
        continuousMutationSnapshot = null
        if (snapshot == currentState) return emptySet()
        undoStack.add(snapshot)
        redoStack.clear()
        return changedPages(snapshot, currentState)
    }

    fun undo(): Set<Int> {
        if (undoStack.isEmpty()) return emptySet()
        continuousMutationSnapshot = null
        val previous = undoStack.removeLast()
        val before = currentState
        redoStack.add(before)
        currentState = previous
        return changedPages(before, previous)
    }

    fun redo(): Set<Int> {
        if (redoStack.isEmpty()) return emptySet()
        continuousMutationSnapshot = null
        val next = redoStack.removeLast()
        val before = currentState
        undoStack.add(before)
        currentState = next
        return changedPages(before, next)
    }

    fun buildSignatureStrokes(
        pageIndex: Int,
        centerX: Float,
        centerY: Float,
        desiredWidth: Float,
        color: Int,
        template: SignatureTemplate
    ): List<InkStroke> {
        if (template.strokes.isEmpty() || template.aspectRatio <= 0f) return emptyList()

        val width = max(desiredWidth, 72f)
        val height = width / template.aspectRatio
        val left = centerX - width / 2f
        val top = centerY - height / 2f

        return template.strokes.mapNotNull { stroke ->
            val scaledPoints = stroke.map { point ->
                PdfPoint(
                    x = left + point.x * width,
                    y = top + point.y * height
                )
            }
            if (scaledPoints.isEmpty()) {
                null
            } else {
                InkStroke(
                    pageIndex = pageIndex,
                    points = scaledPoints,
                    color = color,
                    width = max(width / 48f, 2.5f),
                    isSignature = true
                )
            }
        }
    }

    private fun mutate(defaultPages: Set<Int>, transform: DocumentSessionState.() -> DocumentSessionState): Set<Int> {
        val before = currentState
        val after = before.transform()
        return applyStateChange(before, after, defaultPages)
    }

    private fun applyStateChange(
        before: DocumentSessionState,
        after: DocumentSessionState,
        defaultPages: Set<Int>
    ): Set<Int> {
        if (after == before) return emptySet()
        currentState = after
        if (continuousMutationSnapshot == null) {
            undoStack.add(before)
            redoStack.clear()
        }

        val changed = changedPages(before, after)
        return if (changed.isEmpty()) defaultPages else changed
    }

    private fun changedPages(before: DocumentSessionState, after: DocumentSessionState): Set<Int> {
        val pages = linkedSetOf<Int>()
        pages += before.inkStrokes.map { it.pageIndex }
        pages += before.textHighlights.map { it.pageIndex }
        pages += before.textBoxes.map { it.pageIndex }
        pages += before.notes.map { it.pageIndex }
        pages += after.inkStrokes.map { it.pageIndex }
        pages += after.textHighlights.map { it.pageIndex }
        pages += after.textBoxes.map { it.pageIndex }
        pages += after.notes.map { it.pageIndex }
        return pages
    }

    private fun eraseInkPartially(
        strokes: List<InkStroke>,
        pageIndex: Int,
        x: Float,
        y: Float,
        tolerance: Float,
        targetMode: EraseTargetMode
    ): List<InkStroke> {
        val updated = mutableListOf<InkStroke>()
        val r2 = tolerance * tolerance

        for (stroke in strokes) {
            if (stroke.pageIndex != pageIndex || !matchesEraseTarget(stroke, targetMode)) {
                updated += stroke
                continue
            }

            if (stroke.points.size < 2) {
                val p = stroke.points.firstOrNull()
                if (p != null && distanceSquared(p.x, p.y, x, y) > r2) {
                    updated += stroke
                }
                continue
            }

            val segments = mutableListOf<List<PdfPoint>>()
            var currentSegment = mutableListOf<PdfPoint>()

            val firstInside = distanceSquared(stroke.points[0].x, stroke.points[0].y, x, y) <= r2
            if (!firstInside) {
                currentSegment.add(stroke.points[0])
            }

            for (i in 0 until stroke.points.size - 1) {
                val p1 = stroke.points[i]
                val p2 = stroke.points[i + 1]

                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val fx = p1.x - x
                val fy = p1.y - y

                val a = dx * dx + dy * dy
                val b = 2f * (fx * dx + fy * dy)
                val c = (fx * fx + fy * fy) - r2

                var discriminant = b * b - 4f * a * c

                if (a < 0.0000001f || discriminant < 0f) {
                    // No intersection
                    if (distanceSquared(p2.x, p2.y, x, y) > r2) {
                        currentSegment.add(p2)
                    } else {
                        if (currentSegment.isNotEmpty()) {
                            segments += currentSegment.toList()
                            currentSegment = mutableListOf()
                        }
                    }
                } else {
                    discriminant = kotlin.math.sqrt(discriminant)
                    val t1 = (-b - discriminant) / (2f * a)
                    val t2 = (-b + discriminant) / (2f * a)

                    val tMin = min(t1, t2)
                    val tMax = max(t1, t2)

                    val intersects = tMin <= 1f && tMax >= 0f

                    if (!intersects) {
                        if (distanceSquared(p2.x, p2.y, x, y) > r2) {
                            currentSegment.add(p2)
                        } else {
                            if (currentSegment.isNotEmpty()) {
                                segments += currentSegment.toList()
                                currentSegment = mutableListOf()
                            }
                        }
                    } else {
                        // Slices the vector path precisely at the circle boundary
                        if (tMin > 0f && tMin <= 1f) {
                            currentSegment.add(PdfPoint(p1.x + tMin * dx, p1.y + tMin * dy))
                            segments += currentSegment.toList()
                            currentSegment = mutableListOf()
                        }

                        if (tMax >= 0f && tMax < 1f) {
                            currentSegment.add(PdfPoint(p1.x + tMax * dx, p1.y + tMax * dy))
                        }

                        if (distanceSquared(p2.x, p2.y, x, y) > r2) {
                            currentSegment.add(p2)
                        }
                    }
                }
            }

            if (currentSegment.isNotEmpty()) {
                segments += currentSegment.toList()
            }

            updated += segments.filter { it.isNotEmpty() }.map { segment ->
                normalizeStroke(stroke.copy(id = newAnnotationId(), points = segment))
            }
        }
        return updated
    }

    private fun strokeIntersectsCircle(stroke: InkStroke, cx: Float, cy: Float, r2: Float): Boolean {
        if (stroke.points.isEmpty()) return false
        if (distanceSquared(stroke.points[0].x, stroke.points[0].y, cx, cy) <= r2) return true

        for (i in 0 until stroke.points.size - 1) {
            val p1 = stroke.points[i]
            val p2 = stroke.points[i + 1]

            if (distanceSquared(p2.x, p2.y, cx, cy) <= r2) return true

            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val fx = p1.x - cx
            val fy = p1.y - cy

            val a = dx * dx + dy * dy
            if (a < 0.0000001f) continue

            val b = 2f * (fx * dx + fy * dy)
            val c = (fx * fx + fy * fy) - r2

            val discriminant = b * b - 4f * a * c
            if (discriminant >= 0f) {
                val sqrtD = kotlin.math.sqrt(discriminant)
                val t1 = (-b - sqrtD) / (2f * a)
                val t2 = (-b + sqrtD) / (2f * a)
                if ((t1 in 0f..1f) || (t2 in 0f..1f) || (t1 < 0f && t2 > 1f)) {
                    return true
                }
            }
        }
        return false
    }
}

private val annotationIds = AtomicLong(1L)

fun newAnnotationId(): Long = annotationIds.getAndIncrement()

private fun normalizeStroke(stroke: InkStroke): InkStroke {
    if (stroke.points.isEmpty()) return stroke
    if (stroke.points.size > 1) return stroke
    val onlyPoint = stroke.points.first()
    return stroke.copy(points = listOf(onlyPoint, onlyPoint))
}

private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return dx * dx + dy * dy
}

private fun matchesEraseTarget(stroke: InkStroke, targetMode: EraseTargetMode): Boolean {
    return when (targetMode) {
        EraseTargetMode.ALL -> true
        EraseTargetMode.INK_ONLY -> !stroke.isHighlighter
        EraseTargetMode.HIGHLIGHTS_ONLY -> stroke.isHighlighter
        EraseTargetMode.TEXT_ONLY -> false
    }
}

private fun EraseTargetMode.allowsHighlights(): Boolean {
    return this == EraseTargetMode.ALL || this == EraseTargetMode.HIGHLIGHTS_ONLY
}

private fun EraseTargetMode.allowsText(): Boolean {
    return this == EraseTargetMode.ALL || this == EraseTargetMode.TEXT_ONLY
}
