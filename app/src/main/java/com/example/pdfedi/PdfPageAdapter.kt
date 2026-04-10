package com.example.pdfedi

import android.graphics.*
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfPageAdapter(
    private val document: Document,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var onEraseCompleted: (() -> Unit)? = null

    // --- Callbacks & State for Comments ---
    var onEmptySpaceTapped: ((pageIndex: Int, pdfX: Float, pdfY: Float) -> Unit)? = null
    var onNoteTapped: ((StudyNote) -> Unit)? = null

    var activeNotes: List<StudyNote> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var currentState: EditorState = EditorState()
        set(value) {
            val modeChanged = field.readingMode != value.readingMode
            field = value
            if (modeChanged) clearCache()
        }

    private val renderMutex = Mutex()
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memoryCache = object : LruCache<Int, Bitmap>(maxMemory / 8) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
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
        holder.drawView.isDrawingEnabled = currentState.isDrawingMode && currentState.isEditMode
        holder.drawView.pageIndex = position
        holder.drawView.currentDrawColor = currentState.strokeColor
        holder.drawView.currentStrokeWidth = currentState.strokeWidth
        holder.drawView.isEraserObject = currentState.activeTool == MainActivity.ActiveTool.ERASER_OBJECT
        holder.drawView.isEraserPixel = currentState.activeTool == MainActivity.ActiveTool.ERASER_PIXEL
        holder.drawView.isHighlighter = currentState.activeTool == MainActivity.ActiveTool.HIGHLIGHTER
        holder.drawView.onEraseCompleted = onEraseCompleted

        // Setup Comment Tool Properties
        holder.drawView.isCommentTool = currentState.activeTool == MainActivity.ActiveTool.COMMENT
        holder.drawView.activeNotes = activeNotes.filter { it.pageIndex == position }

        holder.drawView.onEmptySpaceTapped = { pdfX, pdfY ->
            onEmptySpaceTapped?.invoke(position, pdfX, pdfY)
        }
        holder.drawView.onNoteTapped = { note ->
            onNoteTapped?.invoke(note)
        }

        val cachedBitmap = memoryCache.get(position)
        if (cachedBitmap != null) {
            holder.pageImageView.setImageBitmap(cachedBitmap)
            // Still need to trigger a bounds layout check if coming from cache
            holder.drawView.invalidate()
        } else {
            holder.pageImageView.setImageBitmap(null)
        }

        holder.renderJob?.cancel()
        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var finalBitmap: Bitmap? = null
            var pX0 = 0f; var pY0 = 0f; var pW = 0f; var pH = 0f

            renderMutex.withLock {
                try {
                    val page = document.loadPage(position)
                    val pageBounds = page.bounds
                    pX0 = pageBounds.x0
                    pY0 = pageBounds.y0
                    pW = pageBounds.x1 - pageBounds.x0
                    pH = pageBounds.y1 - pageBounds.y0

                    if (cachedBitmap == null) {
                        val ctm = Matrix(2.5f, 0f, 0f, 2.5f, 0f, 0f)
                        val bgColor = when (currentState.readingMode) {
                            ReadingMode.SEPIA -> Color.parseColor("#F4ECD8")
                            ReadingMode.DARK -> Color.parseColor("#121212")
                            else -> Color.WHITE
                        }

                        val width = (pW * 2.5f).toInt()
                        val height = (pH * 2.5f).toInt()

                        val baseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        baseBitmap.eraseColor(Color.WHITE)

                        val dev = AndroidDrawDevice(baseBitmap)
                        page.run(dev, ctm, null as com.artifex.mupdf.fitz.Cookie?)

                        if (currentState.readingMode == ReadingMode.DARK) {
                            finalBitmap = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
                            finalBitmap!!.eraseColor(bgColor)
                            val canvas = Canvas(finalBitmap!!)
                            val paint = Paint().apply {
                                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                                    -1f,  0f,  0f, 0f, 255f,
                                    0f, -1f,  0f, 0f, 255f,
                                    0f,  0f, -1f, 0f, 255f,
                                    0f,  0f,  0f, 1f,   0f
                                )))
                            }
                            canvas.drawBitmap(baseBitmap, 0f, 0f, paint)
                        } else if (currentState.readingMode == ReadingMode.SEPIA) {
                            finalBitmap = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
                            finalBitmap!!.eraseColor(bgColor)
                            val canvas = Canvas(finalBitmap!!)
                            val paint = Paint().apply {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    blendMode = BlendMode.MULTIPLY
                                } else {
                                    @Suppress("DEPRECATION")
                                    xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                                }
                            }
                            canvas.drawBitmap(baseBitmap, 0f, 0f, paint)
                        } else {
                            finalBitmap = baseBitmap
                        }

                        finalBitmap?.let { memoryCache.put(position, it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                holder.drawView.setPdfBounds(pX0, pY0, pW, pH)
                if (finalBitmap != null) {
                    holder.pageImageView.setImageBitmap(finalBitmap)
                    holder.drawView.invalidate()
                }
            }
        }
    }
}