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
    val canvasWidth: Float,
    val canvasHeight: Float,
    val path: Path = Path()
)

object StrokeManager {
    val globalStrokes = mutableListOf<Stroke>()
    val redoStrokes = mutableListOf<Stroke>()

    fun addStroke(stroke: Stroke) {
        globalStrokes.add(stroke)
        redoStrokes.clear()
    }

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
            val next = redoStrokes.removeLast()
            globalStrokes.add(next)
            return next.pageIndex
        }
        return null
    }

    // NEW: Vector Object Eraser Logic
    fun eraseStrokesAt(x: Float, y: Float, pageIndex: Int, radius: Float): Boolean {
        var strokeErased = false
        val iterator = globalStrokes.iterator()

        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (stroke.pageIndex != pageIndex) continue

            // Check if the touch point is close to any point in the stroke
            for (point in stroke.points) {
                val dx = point.x - x
                val dy = point.y - y
                val distanceSquared = dx * dx + dy * dy

                if (distanceSquared <= radius * radius) {
                    // We hit a stroke! Remove it entirely.
                    iterator.remove()

                    // Add to redo stack so the user can 'Undo' their erasure
                    redoStrokes.add(stroke)
                    strokeErased = true
                    break
                }
            }
        }
        return strokeErased
    }
}