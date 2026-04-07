package com.example.pdfedi

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF

data class Stroke(
    val pageIndex: Int,
    val points: MutableList<PointF>,
    val color: Int,
    val width: Float,
    val isEraser: Boolean,
    val isHighlighter: Boolean,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val path: Path = Path(),
    val rects: List<RectF>? = null,
    val isTextHighlight: Boolean = false
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

    fun eraseStrokesAt(x: Float, y: Float, pageIndex: Int, radius: Float): Boolean {
        var strokeErased = false
        val iterator = globalStrokes.iterator()

        while (iterator.hasNext()) {
            val stroke = iterator.next()
            if (stroke.pageIndex != pageIndex) continue

            var hit = false

            // 1. Check if we hit standard freehand strokes (using points)
            for (point in stroke.points) {
                val dx = point.x - x
                val dy = point.y - y
                val distanceSquared = dx * dx + dy * dy

                if (distanceSquared <= radius * radius) {
                    hit = true
                    break
                }
            }

            // 2. Check if we hit text highlights (using rects)
            if (!hit && stroke.isTextHighlight && stroke.rects != null) {
                for (rect in stroke.rects) {
                    // We artificially expand the rectangle slightly by the eraser radius
                    // so it's easier to tap and erase
                    val expandedRect = RectF(
                        rect.left - radius,
                        rect.top - radius,
                        rect.right + radius,
                        rect.bottom + radius
                    )

                    if (expandedRect.contains(x, y)) {
                        hit = true
                        break
                    }
                }
            }

            // If we hit either a point OR a rect, erase the stroke
            if (hit) {
                iterator.remove()
                // Add to redo stack so the user can 'Undo' their erasure
                redoStrokes.add(stroke)
                strokeErased = true
            }
        }
        return strokeErased
    }
}