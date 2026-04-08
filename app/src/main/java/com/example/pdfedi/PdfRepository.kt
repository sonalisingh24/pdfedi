package com.example.pdfedi

import android.content.Context
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
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

    suspend fun saveAnnotationsToPdf(strokes: List<Stroke>): Boolean = withContext(Dispatchers.IO) {
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
                    // Stroke width is natively pre-calculated into PDF units now
                    annot.setBorder(stroke.width)
                    annot.setColor(colorArray)

                    // Highlighters should be highly translucent
                    if (stroke.isHighlighter) {
                        try {
                            annot.setOpacity(0.5f)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // Map pre-aligned PDF coordinates directly into the Fitz Point construct
                    val pdfPoints = stroke.points.map {
                        Point(it.x, it.y)
                    }.toTypedArray()

                    annot.addInkList(pdfPoints)
                    annot.update()
                }
            }

            val pdfDoc = doc as com.artifex.mupdf.fitz.PDFDocument
            pdfDoc.save(cachedPdfFile!!.absolutePath, "incremental")

            originalUri?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    cachedPdfFile!!.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }

            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}