package com.example.pdfedi

import android.graphics.Path
import android.graphics.PointF

data class Stroke(
    val pageIndex: Int,
    val points: MutableList<PointF>,
    val color: Int,
    val width: Float,
    val isEraser: Boolean,
    val isHighlighter: Boolean,
    val path: Path = Path()
)

object StrokeManager {
    val globalStrokes = mutableListOf<Stroke>()
    private val redoStack = mutableListOf<Stroke>()
    private var sessionSnapshot = mutableListOf<Stroke>()

    fun startSession() {
        sessionSnapshot.clear()
        sessionSnapshot.addAll(globalStrokes)
        redoStack.clear()
    }

    fun discardSession() {
        globalStrokes.clear()
        globalStrokes.addAll(sessionSnapshot)
        redoStack.clear()
    }

    fun clearAll() {
        globalStrokes.clear()
        redoStack.clear()
        sessionSnapshot.clear()
    }

    fun addStroke(stroke: Stroke) {
        globalStrokes.add(stroke)
        redoStack.clear()
    }

    fun eraseStrokesAt(x: Float, y: Float, pageIndex: Int, tolerance: Float): Boolean {
        val iterator = globalStrokes.iterator()
        var erased = false
        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (stroke.pageIndex == pageIndex) {
                for (point in stroke.points) {
                    val dx = point.x - x
                    val dy = point.y - y
                    if (dx * dx + dy * dy <= tolerance * tolerance) {
                        iterator.remove()
                        redoStack.add(stroke)
                        erased = true
                        break
                    }
                }
            }
        }
        return erased
    }

    fun undo(): Int? {
        if (globalStrokes.isNotEmpty()) {
            val last = globalStrokes.removeLast()
            redoStack.add(last)
            return last.pageIndex
        }
        return null
    }

    fun redo(): Int? {
        if (redoStack.isNotEmpty()) {
            val stroke = redoStack.removeLast()
            globalStrokes.add(stroke)
            return stroke.pageIndex
        }
        return null
    }
}