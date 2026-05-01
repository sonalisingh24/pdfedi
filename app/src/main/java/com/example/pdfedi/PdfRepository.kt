package com.example.pdfedi

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int
)

data class LoadedPdfDocument(
    val document: Document,
    val strokes: List<InkStroke>,
    val textHighlights: List<TextHighlight>,
    val textBoxes: List<TextBoxAnnotation>,
    val notes: List<StudyNote>,
    val outlineEntries: List<OutlineEntry>
)

class PdfRepository(private val context: Context) {

    var cachedPdfFile: File? = null
        private set
    var originalUri: Uri? = null
        private set
    var mupdfDocument: Document? = null
        private set

    suspend fun createWorkingCopy(uri: Uri, documentUri: String): LoadedPdfDocument? = withContext(Dispatchers.IO) {
        try {
            originalUri = uri
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            val tempFile = File(context.cacheDir, "temp_working_pdf.pdf")
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.use { stream -> stream.copyTo(outputStream) }
            }

            cachedPdfFile = tempFile
            val document = Document.openDocument(tempFile.absolutePath)
            mupdfDocument = document

            val extracted = extractEditableAnnotations(documentUri)
            return@withContext LoadedPdfDocument(
                document = document,
                strokes = extracted.strokes,
                textHighlights = extracted.textHighlights,
                textBoxes = extracted.textBoxes,
                notes = extracted.notes,
                outlineEntries = flattenOutline(document)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun searchDocument(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        val doc = mupdfDocument ?: return@withContext emptyList()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<SearchHit>()
        for (pageIndex in 0 until doc.countPages()) {
            try {
                val page = doc.loadPage(pageIndex)
                val pageHits = page.search(cleanQuery)
                if (pageHits != null) {
                    for (match in pageHits) {
                        val quads = match.map(::toPdfQuad)
                        if (quads.isNotEmpty()) {
                            results += SearchHit(pageIndex = pageIndex, quads = quads)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext results
    }

    suspend fun saveAnnotationsToPdf(session: DocumentSessionState): Boolean = withContext(Dispatchers.IO) {
        try {
            val pdfDoc = mupdfDocument as? PDFDocument ?: return@withContext false

            stripEditableAnnotationsFromWorkingCopy()
            writeSessionToDocument(pdfDoc, session)

            val tempSaveFile = File(context.cacheDir, "safe_save_${System.currentTimeMillis()}.pdf")
            pdfDoc.save(tempSaveFile.absolutePath, "")

            originalUri?.let { uri ->
                try {
                    if (uri.scheme == "file") {
                        val actualFile = File(uri.path!!)
                        tempSaveFile.copyTo(actualFile, overwrite = true)
                        actualFile.setLastModified(System.currentTimeMillis())

                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(actualFile.absolutePath),
                            arrayOf("application/pdf"),
                            null
                        )
                    } else {
                        context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                            tempSaveFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }

                    cachedPdfFile?.let { tempSaveFile.copyTo(it, overwrite = true) }
                    tempSaveFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext false
                }
            }

            stripEditableAnnotationsFromWorkingCopy()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private data class ExtractedEditableData(
        val strokes: List<InkStroke>,
        val textHighlights: List<TextHighlight>,
        val textBoxes: List<TextBoxAnnotation>,
        val notes: List<StudyNote>
    )

    private fun extractEditableAnnotations(documentUri: String): ExtractedEditableData {
        val pdfDoc = mupdfDocument as? PDFDocument ?: return ExtractedEditableData(emptyList(), emptyList(), emptyList(), emptyList())

        val strokes = mutableListOf<InkStroke>()
        val highlights = mutableListOf<TextHighlight>()
        val textBoxes = mutableListOf<TextBoxAnnotation>()
        val notes = mutableListOf<StudyNote>()

        for (pageIndex in 0 until pdfDoc.countPages()) {
            val page = pdfDoc.loadPage(pageIndex) as? PDFPage ?: continue
            val annotations = page.annotations ?: emptyArray()
            val editableAnnotations = mutableListOf<PDFAnnotation>()

            for (annotation in annotations) {
                try {
                    when (annotation.type) {
                        PDFAnnotation.TYPE_INK -> {
                            parseInkAnnotation(pageIndex, annotation)?.let(strokes::addAll)
                            editableAnnotations += annotation
                        }
                        PDFAnnotation.TYPE_HIGHLIGHT -> {
                            parseTextHighlight(pageIndex, annotation)?.let(highlights::add)
                            editableAnnotations += annotation
                        }
                        PDFAnnotation.TYPE_FREE_TEXT -> {
                            parseTextBox(pageIndex, annotation)?.let(textBoxes::add)
                            editableAnnotations += annotation
                        }
                        PDFAnnotation.TYPE_TEXT -> {
                            parseNote(pageIndex, documentUri, annotation)?.let(notes::add)
                            editableAnnotations += annotation
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (editableAnnotations.isNotEmpty()) {
                editableAnnotations.forEach(page::deleteAnnotation)
                page.update()
            }
        }

        return ExtractedEditableData(
            strokes = strokes,
            textHighlights = highlights,
            textBoxes = textBoxes,
            notes = notes
        )
    }

    private fun stripEditableAnnotationsFromWorkingCopy() {
        val pdfDoc = mupdfDocument as? PDFDocument ?: return
        for (pageIndex in 0 until pdfDoc.countPages()) {
            val page = pdfDoc.loadPage(pageIndex) as? PDFPage ?: continue
            val annotations = page.annotations ?: emptyArray()
            var changed = false
            for (annotation in annotations) {
                if (annotation.type == PDFAnnotation.TYPE_INK ||
                    annotation.type == PDFAnnotation.TYPE_HIGHLIGHT ||
                    annotation.type == PDFAnnotation.TYPE_FREE_TEXT ||
                    annotation.type == PDFAnnotation.TYPE_TEXT
                ) {
                    page.deleteAnnotation(annotation)
                    changed = true
                }
            }
            if (changed) {
                page.update()
            }
        }
    }

    private fun writeSessionToDocument(pdfDoc: PDFDocument, session: DocumentSessionState) {
        val changedPages = linkedSetOf<Int>()
        changedPages += session.inkStrokes.map { it.pageIndex }
        changedPages += session.textHighlights.map { it.pageIndex }
        changedPages += session.textBoxes.map { it.pageIndex }
        changedPages += session.notes.map { it.pageIndex }

        val strokesByPage = session.inkStrokes.groupBy { it.pageIndex }
        val highlightsByPage = session.textHighlights.groupBy { it.pageIndex }
        val textBoxesByPage = session.textBoxes.groupBy { it.pageIndex }
        val notesByPage = session.notes.groupBy { it.pageIndex }

        for (pageIndex in changedPages) {
            val page = pdfDoc.loadPage(pageIndex) as? PDFPage ?: continue
            strokesByPage[pageIndex].orEmpty().forEach { saveInkAnnotation(page, it) }
            highlightsByPage[pageIndex].orEmpty().forEach { saveTextHighlight(page, it) }
            textBoxesByPage[pageIndex].orEmpty().forEach { saveTextBox(page, it) }
            notesByPage[pageIndex].orEmpty().forEach { saveNote(page, it) }
            page.update()
        }
    }

    private fun parseInkAnnotation(pageIndex: Int, annotation: PDFAnnotation): List<InkStroke>? {
        val inkLists = annotation.inkList ?: return null
        val color = parseColor(annotation.color)
        val width = try {
            annotation.borderWidth
        } catch (e: Exception) {
            annotation.border
        }
        val opacity = try {
            annotation.opacity
        } catch (e: Exception) {
            1f
        }
        val isHighlighter = opacity < 0.95f

        return inkLists.mapNotNull { path ->
            val points = path.map { PdfPoint(it.x, it.y) }
            if (points.isEmpty()) {
                null
            } else {
                InkStroke(
                    pageIndex = pageIndex,
                    points = points,
                    color = color,
                    width = width,
                    isHighlighter = isHighlighter
                )
            }
        }
    }

    private fun parseTextHighlight(pageIndex: Int, annotation: PDFAnnotation): TextHighlight? {
        val quadPoints = annotation.quadPoints ?: return null
        val color = parseColor(annotation.color)
        val opacity = try {
            annotation.opacity
        } catch (e: Exception) {
            0.32f
        }
        val quads = quadPoints.map(::toPdfQuad)
        if (quads.isEmpty()) return null
        return TextHighlight(
            pageIndex = pageIndex,
            quads = quads,
            color = color,
            opacity = opacity
        )
    }

    private fun parseNote(pageIndex: Int, documentUri: String, annotation: PDFAnnotation): StudyNote? {
        val rect = annotation.rect ?: return null
        val content = annotation.contents ?: return null
        if (content.isBlank()) return null
        return StudyNote(
            documentUri = documentUri,
            pageIndex = pageIndex,
            x = rect.x0,
            y = rect.y1,
            textContent = content
        )
    }

    private fun saveInkAnnotation(page: PDFPage, stroke: InkStroke) {
        val annotation = page.createAnnotation(PDFAnnotation.TYPE_INK)
        annotation.setBorderWidth(stroke.width)
        annotation.setColor(toPdfColor(stroke.color))
        if (stroke.isHighlighter) {
            annotation.setOpacity(0.32f)
        }

        val points = stroke.points.ifEmpty { listOf(PdfPoint(0f, 0f), PdfPoint(0f, 0f)) }
        val pdfPoints = points.map { Point(it.x, it.y) }.toTypedArray()
        annotation.addInkList(pdfPoints)
        annotation.update()
    }

    private fun saveTextHighlight(page: PDFPage, highlight: TextHighlight) {
        if (highlight.quads.isEmpty()) return
        val annotation = page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)
        annotation.setColor(toPdfColor(highlight.color))
        annotation.setOpacity(highlight.opacity)
        annotation.setQuadPoints(highlight.quads.map(::toMupdfQuad).toTypedArray())
        annotation.update()
    }

    private fun parseTextBox(pageIndex: Int, annotation: PDFAnnotation): TextBoxAnnotation? {
        val rect = annotation.rect ?: return null
        val text = annotation.contents ?: return null
        if (text.isBlank()) return null

        val savedFontSize = try {
            annotation.author?.toFloatOrNull() ?: 16f
        } catch (e: Exception) {
            16f
        }

        return TextBoxAnnotation(
            pageIndex = pageIndex,
            rect = PdfRect(rect.x0, rect.y0, rect.x1, rect.y1),
            text = text,
            color = parseColor(annotation.color),
            fontSize = savedFontSize
        )
    }

    private fun saveTextBox(page: PDFPage, textBox: TextBoxAnnotation) {
        val annotation = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        annotation.setRect(Rect(textBox.rect.left, textBox.rect.top, textBox.rect.right, textBox.rect.bottom))
        annotation.setContents(textBox.text)
        annotation.setColor(toPdfColor(textBox.color))
        annotation.setBorderWidth(0f)

        try {
            annotation.author = textBox.fontSize.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        annotation.update()
    }

    private fun saveNote(page: PDFPage, note: StudyNote) {
        val iconSize = 22f
        val annotation = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
        annotation.setRect(Rect(note.x, note.y - iconSize, note.x + iconSize, note.y))
        annotation.setContents(note.textContent)
        annotation.setColor(toPdfColor(Color.parseColor("#FBC02D")))
        annotation.setIsOpen(false)
        try {
            annotation.setIcon("Comment")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        annotation.update()
    }

    private fun flattenOutline(document: Document): List<OutlineEntry> {
        val outline = try {
            document.loadOutline()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return emptyList()

        val entries = mutableListOf<OutlineEntry>()
        walkOutline(document, outline, 0, entries)
        return entries
    }

    private fun walkOutline(
        document: Document,
        outline: Array<Outline>?,
        level: Int,
        entries: MutableList<OutlineEntry>
    ) {
        outline.orEmpty().forEach { current ->
            val title = current.title?.takeIf { it.isNotBlank() } ?: "Untitled"
            val pageIndex = resolveOutlinePage(document, current)
            if (pageIndex >= 0) {
                entries += OutlineEntry(title = title, pageIndex = pageIndex, level = level)
            }
            walkOutline(document, current.down, level + 1, entries)
        }
    }

    private fun resolveOutlinePage(document: Document, outline: Outline): Int {
        return try {
            val location = document.resolveLink(outline.uri)
            document.pageNumberFromLocation(location)
        } catch (e: Exception) {
            -1
        }
    }

    private fun parseColor(colorArray: FloatArray?): Int {
        if (colorArray == null || colorArray.size < 3) return Color.BLACK
        return Color.rgb(
            (colorArray[0] * 255).toInt().coerceIn(0, 255),
            (colorArray[1] * 255).toInt().coerceIn(0, 255),
            (colorArray[2] * 255).toInt().coerceIn(0, 255)
        )
    }

    private fun toPdfColor(color: Int): FloatArray {
        return floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f
        )
    }

    private fun toPdfQuad(quad: Quad): PdfQuad {
        return PdfQuad(
            ulX = quad.ul_x,
            ulY = quad.ul_y,
            urX = quad.ur_x,
            urY = quad.ur_y,
            llX = quad.ll_x,
            llY = quad.ll_y,
            lrX = quad.lr_x,
            lrY = quad.lr_y
        )
    }

    private fun toMupdfQuad(quad: PdfQuad): Quad {
        return Quad(
            quad.ulX,
            quad.ulY,
            quad.urX,
            quad.urY,
            quad.llX,
            quad.llY,
            quad.lrX,
            quad.lrY
        )
    }
}
