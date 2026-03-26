package com.example.pdfedi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CustomDrawView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // THE SWITCH: False = Scrolling, True = Drawing
    var isDrawingEnabled = false

    // 1. Set up the red pen
    private val drawPaint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // 2. Track the drawing paths
    private var currentPath = Path()
    private val paths = mutableListOf<Path>()

    // 3. Draw the lines on the screen
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (path in paths) {
            canvas.drawPath(path, drawPaint)
        }
        canvas.drawPath(currentPath, drawPaint)
    }

    // 4. HERE IS THE MISSING FUNCTION! This listens for your finger.
    override fun onTouchEvent(event: MotionEvent): Boolean {

        // If drawing is disabled, ignore the touch so the RecyclerView can scroll!
        if (!isDrawingEnabled) return false

        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.moveTo(touchX, touchY)
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(touchX, touchY)
            }
            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(touchX, touchY)
                paths.add(currentPath)
                currentPath = Path() // Reset for the next line
            }
            else -> return false
        }

        invalidate() // Tell Android to redraw the screen
        return true
    }
}