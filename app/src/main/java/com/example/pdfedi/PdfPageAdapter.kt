package com.example.pdfedi

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    // NEW: File reference needed to extract text via PDFBox
    var pdfFile: File? = null
    private val textBoundsCache = mutableMapOf<Int, List<RectF>>()

    var currentState: EditorState = EditorState()
        set(value) {
            val modeChanged = field.readingMode != value.readingMode
            field = value
            if (modeChanged) {
                clearCache()
            }
        }

    private val renderMutex = Mutex()

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
        notifyDataSetChanged()
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pageImageView: ImageView = itemView.findViewById(R.id.pageImageView)
        val drawView: CustomDrawView = itemView.findViewById(R.id.drawView)
        var renderJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun getItemCount(): Int = pageCount

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Pass tool states to DrawView
        holder.drawView.isDrawingEnabled = currentState.isDrawingMode
        holder.drawView.pageIndex = position
        holder.drawView.currentDrawColor = currentState.strokeColor
        holder.drawView.currentStrokeWidth = currentState.strokeWidth
        holder.drawView.isEraser = currentState.activeTool == MainActivity.ActiveTool.ERASER

        // Pass highlighting state. Assume TEXT_HIGHLIGHTER is added to ActiveTool enum
        holder.drawView.isHighlighter = currentState.activeTool == MainActivity.ActiveTool.HIGHLIGHTER
        holder.drawView.isTextHighlighter = currentState.activeTool == MainActivity.ActiveTool.TEXT_HIGHLIGHTER

        holder.drawView.isNoteTool = currentState.activeTool == MainActivity.ActiveTool.NOTE
        holder.drawView.currentNotes = currentState.studyNotes.filter { it.pageIndex == position }

        // Setup Text Bounds Cache
        if (textBoundsCache.containsKey(position)) {
            holder.drawView.pageTextLines = textBoundsCache[position] ?: emptyList()
        } else {
            holder.drawView.pageTextLines = emptyList()
        }

        val cachedBitmap = memoryCache.get(position)
        if (cachedBitmap != null) {
            holder.pageImageView.setImageBitmap(cachedBitmap)
            holder.drawView.invalidate()
            return
        }

        holder.pageImageView.setImageBitmap(null)
        holder.renderJob?.cancel()

        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var finalBitmap: Bitmap? = null

            // 1. Extract Text Bounds asynchronously
            if (!textBoundsCache.containsKey(position) && pdfFile != null) {
                val bounds = PdfTextExtractor.extractTextLines(pdfFile!!, position, 2.5f)
                textBoundsCache[position] = bounds
            }

            // 2. Render Bitmap
            renderMutex.withLock {
                try {
                    val page = pdfRenderer.openPage(position)
                    val width = (page.width * 2.5).toInt()
                    val height = (page.height * 2.5).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                    val bgColor = when (currentState.readingMode) {
                        ReadingMode.SEPIA -> Color.parseColor("#F4ECD8")
                        ReadingMode.DARK -> Color.parseColor("#121212")
                        else -> Color.WHITE
                    }
                    bitmap.eraseColor(bgColor)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    if (currentState.readingMode == ReadingMode.DARK) {
                        finalBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(finalBitmap!!)
                        val paint = Paint()

                        val colorMatrix = ColorMatrix(floatArrayOf(
                            -1f,  0f,  0f, 0f, 255f,
                            0f, -1f,  0f, 0f, 255f,
                            0f,  0f, -1f, 0f, 255f,
                            0f,  0f,  0f, 1f,   0f
                        ))
                        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                        canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    } else {
                        finalBitmap = bitmap
                    }

                    finalBitmap?.let { memoryCache.put(position, it) }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Update UI on Main Thread
            withContext(Dispatchers.Main) {
                finalBitmap?.let {
                    holder.pageImageView.setImageBitmap(it)
                    // Assign the newly loaded bounds to the view
                    holder.drawView.pageTextLines = textBoundsCache[position] ?: emptyList()
                    holder.drawView.invalidate()
                }
            }
        }
    }
}