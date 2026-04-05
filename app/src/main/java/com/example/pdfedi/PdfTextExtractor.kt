// Create new file: PdfTextExtractor.kt
package com.example.pdfedi

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

object PdfTextExtractor {
    fun extractTextLines(file: File, pageIndex: Int, scale: Float = 2.5f): List<RectF> {
        val rects = mutableListOf<RectF>()
        try {
            PDDocument.load(file).use { document ->
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                        super.writeString(text, textPositions)
                        if (textPositions.isNullOrEmpty()) return

                        var minX = Float.MAX_VALUE
                        var minY = Float.MAX_VALUE
                        var maxX = Float.MIN_VALUE
                        var maxY = Float.MIN_VALUE

                        for (pos in textPositions) {
                            // Convert PDF points to Android View coordinates using your scale
                            val x = pos.xDirAdj * scale
                            val y = (pos.yDirAdj - pos.heightDir) * scale
                            val w = pos.widthDirAdj * scale
                            val h = pos.heightDir * scale

                            if (x < minX) minX = x
                            if (y < minY) minY = y
                            if (x + w > maxX) maxX = x + w
                            if (y + h > maxY) maxY = y + h
                        }

                        // Add slight padding to make the highlight look natural
                        val padding = 4f
                        rects.add(RectF(minX - padding, minY - padding, maxX + padding, maxY + padding))
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = pageIndex + 1 // PDFBox pages are 1-indexed
                stripper.endPage = pageIndex + 1
                stripper.getText(document)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rects
    }
}