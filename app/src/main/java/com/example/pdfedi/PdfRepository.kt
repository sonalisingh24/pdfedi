package com.example.pdfedi

import android.content.Context
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
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

    // ====================================================
    // PHASE 5: NATIVE SEARCH
    // ====================================================
    suspend fun searchPdfForText(query: String): List<Int> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Int>()
        mupdfDocument?.let { doc ->
            for (i in 0 until doc.countPages()) {
                val page = doc.loadPage(i)
                val textPage = page.toStructuredText()

                // MuPDF makes searching incredibly easy
                val hits = textPage.search(query)
                if (hits != null && hits.isNotEmpty()) {
                    results.add(i)
                }

            }
        }
        return@withContext results
    }

    // ====================================================
    // PHASE 5: BAKING NATIVE PDF ANNOTATIONS
    // ====================================================
    suspend fun saveAnnotationsToPdf(strokes: List<Stroke>): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = mupdfDocument ?: return@withContext false
            val scale = 2.5f // We must reverse your canvas scaling!

            // Group the strokes so we only have to open each page once
            val strokesByPage = strokes.groupBy { it.pageIndex }

            for ((pageIndex, pageStrokes) in strokesByPage) {
                // FIXED: Cast to PDFPage to allow annotation creation
                val page = doc.loadPage(pageIndex) as PDFPage

                for (stroke in pageStrokes) {
                    val r = android.graphics.Color.red(stroke.color) / 255f
                    val g = android.graphics.Color.green(stroke.color) / 255f
                    val b = android.graphics.Color.blue(stroke.color) / 255f
                    val colorArray = floatArrayOf(r, g, b)

                    if (stroke.isTextHighlight && stroke.rects != null) {
                        for (rect in stroke.rects) {
                            // FIXED: Use PDFAnnotation.TYPE_HIGHLIGHT
                            val annot = page.createAnnotation(PDFAnnotation.TYPE_HIGHLIGHT)

                            val p = 4f
                            val pdfRect = Rect(
                                (rect.left + p) / scale,
                                (rect.top + p) / scale,
                                (rect.right - p) / scale,
                                (rect.bottom - p) / scale
                            )

                            annot.setRect(pdfRect)
                            annot.setColor(colorArray)
                            annot.update()
                        }
                    } else {
                        // FIXED: Use PDFAnnotation.TYPE_INK
                        val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)
                        annot.setBorder(stroke.width / scale)
                        annot.setColor(colorArray)

                        val pdfPoints = stroke.points.map {
                            Point(it.x / scale, it.y / scale)
                        }.toTypedArray()

                        annot.addInkList(pdfPoints)
                        annot.update()
                    }
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

            // Clear our temporary UI strokes because they are now permanent in the PDF!
            StrokeManager.globalStrokes.clear()

            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}