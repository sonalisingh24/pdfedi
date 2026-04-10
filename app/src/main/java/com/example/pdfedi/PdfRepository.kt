package com.example.pdfedi

import android.content.Context
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfRepository(private val context: Context) {

    var cachedPdfFile: File? = null
    var originalUri: Uri? = null
    var mupdfDocument: Document? = null

    suspend fun createWorkingCopy(uri: Uri): Document? = withContext(Dispatchers.IO) {
        try {
            originalUri = uri
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = File(context.cacheDir, "temp_working_pdf.pdf")
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()

            cachedPdfFile = tempFile
            mupdfDocument = Document.openDocument(tempFile.absolutePath)
            return@withContext mupdfDocument
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun saveAnnotationsToPdf(strokes: List<Stroke>, notes: List<StudyNote>): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = mupdfDocument ?: return@withContext false

            val strokesByPage = strokes.groupBy { it.pageIndex }

            for ((pageIndex, pageStrokes) in strokesByPage) {
                val page = doc.loadPage(pageIndex) as PDFPage

                for (stroke in pageStrokes) {
                    val r = android.graphics.Color.red(stroke.color) / 255f
                    val g = android.graphics.Color.green(stroke.color) / 255f
                    val b = android.graphics.Color.blue(stroke.color) / 255f
                    val colorArray = floatArrayOf(r, g, b)

                    val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)

                    annot.border = stroke.width
                    annot.color = colorArray

                    if (stroke.isHighlighter) {
                        try {
                            annot.opacity = 0.5f
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    val pdfPoints = stroke.points.map {
                        Point(it.x, it.y)
                    }.toTypedArray()

                    annot.addInkList(pdfPoints)
                    annot.update()
                }
            }

            // Save Notes as standard PDF Text Annotations (Sticky Notes)
            val notesByPage = notes.groupBy { it.pageIndex }
            for ((pageIndex, pageNotes) in notesByPage) {
                val page = doc.loadPage(pageIndex) as PDFPage

                for (note in pageNotes) {
                    // TYPE_TEXT represents standard sticky note comments
                    val annot = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
                    annot.contents = note.textContent

                    // Define the bounding box for the comment icon in PDF coordinates
                    val rect = com.artifex.mupdf.fitz.Rect(note.x, note.y, note.x + 24f, note.y + 24f)
                    annot.rect = rect

                    // Set the color of the sticky note icon to a standard Yellow
                    annot.color = floatArrayOf(1f, 0.8f, 0f)

                    annot.update()
                }
            }

            val pdfDoc = doc as com.artifex.mupdf.fitz.PDFDocument
            pdfDoc.save(cachedPdfFile!!.absolutePath, "incremental")

            originalUri?.let { uri ->
                try {
                    if (uri.scheme == "file") {
                        val actualFile = File(uri.path!!)

                        cachedPdfFile!!.copyTo(actualFile, overwrite = true)

                        actualFile.setLastModified(System.currentTimeMillis())

                        android.media.MediaScannerConnection.scanFile(
                            context,
                            arrayOf(actualFile.absolutePath),
                            arrayOf("application/pdf"),
                            null
                        )
                    } else {
                        context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                            cachedPdfFile!!.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext false
                }
            }

            return@withContext true

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}