package com.example.pdfedi

import android.graphics.Path
import android.graphics.PointF

// Remembers exactly how a line was drawn, on what page, and its coordinates for saving
data class Stroke(
    val pageIndex: Int,
    val points: MutableList<PointF>,
    val color: Int,
    val width: Float,
    val isEraser: Boolean,
    val isHighlighter: Boolean,
    var path: Path = Path() // For fast Android drawing
)

object StrokeManager {
    val globalStrokes = mutableListOf<Stroke>()
    val redoStrokes = mutableListOf<Stroke>()

    fun addStroke(stroke: Stroke) {
        globalStrokes.add(stroke)
        redoStrokes.clear() // If you draw a new line, you lose your redo future
    }

    // Returns the page number that was modified so we can instantly refresh it
    fun undo(): Int? {
        if (globalStrokes.isNotEmpty()) {
            val last = globalStrokes.removeLast()
            redoStrokes.add(last)
            return last.pageIndex
        }
        return null
    }

    fun redo(): Int? {
        if (redoStrokes.isNotEmpty()) {
            val stroke = redoStrokes.removeLast()
            globalStrokes.add(stroke)
            return stroke.pageIndex
        }
        return null
    }
}