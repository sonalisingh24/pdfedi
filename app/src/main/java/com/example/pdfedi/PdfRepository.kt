package com.example.pdfedi

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfRepository(private val context: Context) {

    var cachedPdfFile: File? = null
    var originalUri: Uri? = null

    suspend fun createWorkingCopy(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            originalUri = uri
            val inputStream = context.contentResolver.openInputStream(uri)
            val tempFile = File(context.cacheDir, "temp_working_pdf.pdf")
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            cachedPdfFile = tempFile
            return@withContext tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // NEW Phase 5: Text Extraction and Search
    suspend fun searchPdfForText(query: String): List<Int> = withContext(Dispatchers.IO) {
        val matchingPages = mutableListOf<Int>()
        if (cachedPdfFile == null) return@withContext matchingPages

        try {
            val document = PDDocument.load(cachedPdfFile)
            val stripper = PDFTextStripper()

            // Loop through pages. PDFBox pages are 1-indexed.
            for (i in 1..document.numberOfPages) {
                stripper.startPage = i
                stripper.endPage = i
                val textOnPage = stripper.getText(document)

                if (textOnPage.contains(query, ignoreCase = true)) {
                    matchingPages.add(i - 1) // Store as 0-indexed for the RecyclerView Adapter
                }
            }
            document.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext matchingPages
    }

    suspend fun saveAnnotationsToPdf(strokes: List<Stroke>): Boolean = withContext(Dispatchers.IO) {
        if (cachedPdfFile == null || originalUri == null) return@withContext false
        try {
            val document = PDDocument.load(cachedPdfFile)
            for (stroke in strokes) {
                if (stroke.isEraser || stroke.points.isEmpty()) continue

                val page = document.getPage(stroke.pageIndex)
                val contentStream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)

                if (stroke.isHighlighter) {
                    val graphicsState = PDExtendedGraphicsState()
                    graphicsState.strokingAlphaConstant = 0.4f
                    contentStream.setGraphicsStateParameters(graphicsState)
                }

                contentStream.setStrokingColor(Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))

                val pdfWidth = page.mediaBox.width
                val pdfHeight = page.mediaBox.height
                val scaleX = pdfWidth / stroke.canvasWidth
                val scaleY = pdfHeight / stroke.canvasHeight

                contentStream.setLineWidth(stroke.width * scaleX)

                val start = stroke.points[0]
                contentStream.moveTo(start.x * scaleX, pdfHeight - (start.y * scaleY))

                for (i in 1 until stroke.points.size) {
                    val pt = stroke.points[i]
                    contentStream.lineTo(pt.x * scaleX, pdfHeight - (pt.y * scaleY))
                }

                contentStream.stroke()
                contentStream.close()
            }
            val outputStream = context.contentResolver.openOutputStream(originalUri!!, "wt")
            if (outputStream != null) {
                document.save(outputStream)
                outputStream.close()
            }
            document.close()
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}