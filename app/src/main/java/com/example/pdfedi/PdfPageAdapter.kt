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

class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var currentState: EditorState = EditorState()
        set(value) {
            val modeChanged = field.readingMode != value.readingMode
            field = value
            if (modeChanged) {
                clearCache() // If Sepia/Dark mode is toggled, invalidate the cached images
            }
        }

    private val renderMutex = Mutex()

    // NEW Phase 5: RAM Cache for Smooth Scrolling
    // Use 1/8th of the available device memory for this memory cache.
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items.
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
        // Pass drawing state to the DrawView
        holder.drawView.isDrawingEnabled = currentState.isDrawingMode
        holder.drawView.pageIndex = position
        holder.drawView.currentDrawColor = currentState.strokeColor
        holder.drawView.currentStrokeWidth = currentState.strokeWidth
        holder.drawView.isEraser = currentState.activeTool == MainActivity.ActiveTool.ERASER
        holder.drawView.isHighlighter = currentState.activeTool == MainActivity.ActiveTool.HIGHLIGHTER

        // Pass Notes configuration (Phase 4)
        holder.drawView.isNoteTool = currentState.activeTool == MainActivity.ActiveTool.NOTE
        holder.drawView.currentNotes = currentState.studyNotes.filter { it.pageIndex == position }

        // CACHE LOGIC: Check if this page is already built in memory
        val cachedBitmap = memoryCache.get(position)
        if (cachedBitmap != null) {
            holder.pageImageView.setImageBitmap(cachedBitmap)
            holder.drawView.invalidate() // Force notes/drawings to appear on top
            return
        }

        // If not cached, clear view and render it
        holder.pageImageView.setImageBitmap(null)
        holder.renderJob?.cancel()

        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var finalBitmap: Bitmap? = null

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

                    // Add the finished rendering to our RAM Cache
                    finalBitmap?.let { memoryCache.put(position, it) }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                finalBitmap?.let {
                    holder.pageImageView.setImageBitmap(it)
                    holder.drawView.invalidate()
                }
            }
        }
    }
}