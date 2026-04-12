package com.example.pdfedi

import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
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

            loadExistingAnnotationsToStrokes()

            return@withContext mupdfDocument
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun loadExistingAnnotationsToStrokes() = withContext(Dispatchers.IO) {
        try {
            val doc = mupdfDocument as? com.artifex.mupdf.fitz.PDFDocument ?: return@withContext

            StrokeManager.clearAll()

            val numPages = doc.countPages()
            for (i in 0 until numPages) {
                val page = doc.loadPage(i) as? PDFPage ?: continue
                val annots = page.annotations ?: continue

                val annotsToDelete = mutableListOf<PDFAnnotation>()

                for (annot in annots) {
                    if (annot.type == PDFAnnotation.TYPE_INK) {

                        val colorArray = annot.color
                        var strokeColor = android.graphics.Color.BLACK
                        if (colorArray != null && colorArray.size >= 3) {
                            strokeColor = android.graphics.Color.rgb(
                                (colorArray[0] * 255).toInt(),
                                (colorArray[1] * 255).toInt(),
                                (colorArray[2] * 255).toInt()
                            )
                        }

                        val width = annot.border
                        val isHighlighter = try { annot.opacity < 1.0f } catch (e: Exception) { false }

                        try {
                            val inkLists = annot.inkList
                            if (inkLists != null) {
                                for (pathArray in inkLists) {
                                    val points = mutableListOf<PointF>()
                                    val strokePath = Path()

                                    if (pathArray.isNotEmpty()) {
                                        strokePath.moveTo(pathArray[0].x, pathArray[0].y)
                                        for (pt in pathArray) {
                                            points.add(PointF(pt.x, pt.y))
                                        }
                                        for (j in 1 until pathArray.size) {
                                            strokePath.lineTo(pathArray[j].x, pathArray[j].y)
                                        }
                                    }

                                    val stroke = Stroke(
                                        pageIndex = i,
                                        points = points,
                                        color = strokeColor,
                                        width = width,
                                        isEraser = false,
                                        isHighlighter = isHighlighter,
                                        path = strokePath
                                    )
                                    StrokeManager.addStroke(stroke)
                                }
                                annotsToDelete.add(annot)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                for (annotToDelete in annotsToDelete) {
                    page.deleteAnnotation(annotToDelete)
                }

                if (annotsToDelete.isNotEmpty()) {
                    page.update()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    suspend fun saveAnnotationsToPdf(strokes: List<Stroke>, notes: List<StudyNote>): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = mupdfDocument ?: return@withContext false
            val pdfDoc = doc as com.artifex.mupdf.fitz.PDFDocument

            // FIX 1: Clean the slate. Delete any existing ink annotations from the open memory
            // document before saving. This prevents duplicate strokes if the user saves multiple times.
            val numPages = pdfDoc.countPages()
            for (i in 0 until numPages) {
                val page = pdfDoc.loadPage(i) as? PDFPage ?: continue
                val annots = page.annotations ?: continue

                val annotsToDelete = annots.filter { it.type == PDFAnnotation.TYPE_INK }
                for (annot in annotsToDelete) {
                    page.deleteAnnotation(annot)
                }
                if (annotsToDelete.isNotEmpty()) {
                    page.update()
                }
            }

            // Apply the current StrokeManager state to the document
            val strokesByPage = strokes.groupBy { it.pageIndex }

            for ((pageIndex, pageStrokes) in strokesByPage) {
                val page = pdfDoc.loadPage(pageIndex) as PDFPage

                for (stroke in pageStrokes) {
                    val r = android.graphics.Color.red(stroke.color) / 255f
                    val g = android.graphics.Color.green(stroke.color) / 255f
                    val b = android.graphics.Color.blue(stroke.color) / 255f
                    val colorArray = floatArrayOf(r, g, b)

                    val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)
                    annot.border = stroke.width
                    annot.color = colorArray

                    if (stroke.isHighlighter) {
                        try { annot.opacity = 0.5f } catch (e: Exception) { e.printStackTrace() }
                    }

                    val pdfPoints = stroke.points.map { Point(it.x, it.y) }.toTypedArray()
                    annot.addInkList(pdfPoints)
                    annot.update()
                }
            }

            // FIX 2: Use "" instead of "incremental" to force a clean, full structural rewrite
            // of the PDF to the new temporary file.
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

                    tempSaveFile.copyTo(cachedPdfFile!!, overwrite = true)
                    tempSaveFile.delete()

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