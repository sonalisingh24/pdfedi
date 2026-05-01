package com.example.pdfedi

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.example.pdfedi.database.StudyNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfPageAdapter(
    private val document: Document,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    var onSessionChanged: ((Set<Int>) -> Unit)? = null
    var onCommentRequested: ((pageIndex: Int, pdfX: Float, pdfY: Float) -> Unit)? = null
    var onNoteTapped: ((StudyNote) -> Unit)? = null
    var onTextBoxRequested: ((pageIndex: Int, pdfX: Float, pdfY: Float) -> Unit)? = null
    var onTextBoxTapped: ((TextBoxAnnotation) -> Unit)? = null
    var signatureTemplateProvider: (() -> SignatureTemplate?)? = null

    var currentState: EditorState = EditorState()
        set(value) {
            val cacheSensitiveChanged = field.readingMode != value.readingMode
            field = value
            if (cacheSensitiveChanged) clearCache()
        }

    var searchResults: List<SearchHit> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectedSearchHitId: Long? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var renderScale: Float = 2.5f
        set(value) {
            val quantized = value.coerceIn(2.5f, 5f)
            if (kotlin.math.abs(field - quantized) < 0.1f) return
            field = quantized
            clearCache()
        }

    private val renderMutex = Mutex()
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memoryCache = object : LruCache<Int, Bitmap>(maxMemory / 8) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }
    private val textCache = mutableMapOf<Int, StructuredText>()

    fun clearCache() {
        memoryCache.evictAll()
        notifyItemRangeChanged(0, pageCount, "SMOOTH_UPDATE")
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

    override fun onBindViewHolder(holder: PageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("SMOOTH_UPDATE")) {
            bindViewHolderInternal(holder, position, clearExistingImage = false)
        } else {
            bindViewHolderInternal(holder, position, clearExistingImage = true)
        }
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        bindViewHolderInternal(holder, position, clearExistingImage = true)
    }

    private fun bindViewHolderInternal(holder: PageViewHolder, position: Int, clearExistingImage: Boolean) {
        holder.drawView.isEditMode = currentState.isEditMode
        holder.drawView.isDrawingEnabled = currentState.isEditMode && currentState.isDrawingMode
        holder.drawView.pageIndex = position
        holder.drawView.currentDrawColor = currentState.strokeColor
        holder.drawView.currentStrokeWidth = currentState.strokeWidth
        holder.drawView.pageRenderScale = renderScale
        holder.drawView.isEraserObject = currentState.activeTool == MainActivity.ActiveTool.ERASER_OBJECT
        holder.drawView.isEraserPixel = currentState.activeTool == MainActivity.ActiveTool.ERASER_PIXEL
        holder.drawView.isHighlighter = currentState.activeTool == MainActivity.ActiveTool.HIGHLIGHTER
        holder.drawView.isCommentTool = currentState.activeTool == MainActivity.ActiveTool.COMMENT && currentState.isEditMode
        holder.drawView.isTextBoxTool = currentState.activeTool == MainActivity.ActiveTool.TEXT_BOX && currentState.isEditMode
        holder.drawView.isSignatureTool = currentState.activeTool == MainActivity.ActiveTool.SIGNATURE && currentState.isEditMode
        holder.drawView.isLineLockEnabled = currentState.isLineLockEnabled
        holder.drawView.eraserTargetMode = currentState.eraserTargetMode
        holder.drawView.onSessionChanged = onSessionChanged
        holder.drawView.onCommentRequested = { pdfX, pdfY ->
            onCommentRequested?.invoke(position, pdfX, pdfY)
        }
        holder.drawView.onNoteTapped = { note -> onNoteTapped?.invoke(note) }
        holder.drawView.onTextBoxRequested = { pdfX, pdfY ->
            onTextBoxRequested?.invoke(position, pdfX, pdfY)
        }
        holder.drawView.onTextBoxTapped = { textBox ->
            onTextBoxTapped?.invoke(textBox)
        }
        holder.drawView.signatureTemplateProvider = signatureTemplateProvider
        holder.drawView.searchMatches = searchResults.filter { it.pageIndex == position }
        holder.drawView.selectedSearchHitId = selectedSearchHitId
        holder.drawView.textHighlightProvider = provider@{ start, end ->
            val text = textCache[position] ?: return@provider emptyList()
            try {
                text.highlight(Point(start.x, start.y), Point(end.x, end.y))
                    ?.map(::toPdfQuad)
                    .orEmpty()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        val cachedBitmap = memoryCache.get(position)
        if (cachedBitmap != null) {
            holder.pageImageView.setImageBitmap(cachedBitmap)
            holder.drawView.invalidate()
        } else if (clearExistingImage) {
            // ONLY clear the image if we are scrolling normally, NOT when zooming/updating smoothly
            holder.pageImageView.setImageBitmap(null)
        }

        holder.renderJob?.cancel()
        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var finalBitmap: Bitmap? = null
            var pX0 = 0f
            var pY0 = 0f
            var pW = 0f
            var pH = 0f

            renderMutex.withLock {
                try {
                    val page = document.loadPage(position)
                    val pageBounds = page.bounds
                    pX0 = pageBounds.x0
                    pY0 = pageBounds.y0
                    pW = pageBounds.x1 - pageBounds.x0
                    pH = pageBounds.y1 - pageBounds.y0

                    if (!textCache.containsKey(position)) {
                        try {
                            textCache[position] = page.toStructuredText()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (cachedBitmap == null) {
                        val ctm = Matrix(renderScale, 0f, 0f, renderScale, 0f, 0f)
                        val bgColor = when (currentState.readingMode) {
                            ReadingMode.SEPIA -> "#F4ECD8".toColorInt()
                            ReadingMode.DARK -> "#121212".toColorInt()
                            else -> Color.WHITE
                        }

                        val width = (pW * renderScale).toInt().coerceAtLeast(1)
                        val height = (pH * renderScale).toInt().coerceAtLeast(1)

                        val baseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        baseBitmap.eraseColor(Color.WHITE)

                        val device = AndroidDrawDevice(baseBitmap)
                        page.run(device, ctm, null)

                        finalBitmap = when (currentState.readingMode) {
                            ReadingMode.DARK -> createDarkBitmap(baseBitmap, bgColor)
                            ReadingMode.SEPIA -> createSepiaBitmap(baseBitmap, bgColor)
                            else -> baseBitmap
                        }

                        finalBitmap?.let { memoryCache.put(position, it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                holder.drawView.pageRenderScale = renderScale
                holder.drawView.setPdfBounds(pX0, pY0, pW, pH)
                if (finalBitmap != null) {
                    holder.pageImageView.setImageBitmap(finalBitmap)
                    holder.drawView.invalidate()
                }
            }
        }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        super.onViewRecycled(holder)
        holder.renderJob?.cancel()
    }

    private fun createDarkBitmap(baseBitmap: Bitmap, bgColor: Int): Bitmap {
        val finalBitmap = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
        finalBitmap.eraseColor(bgColor)
        val canvas = Canvas(finalBitmap)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        canvas.drawBitmap(baseBitmap, 0f, 0f, paint)
        return finalBitmap
    }

    private fun createSepiaBitmap(baseBitmap: Bitmap, bgColor: Int): Bitmap {
        val finalBitmap = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
        finalBitmap.eraseColor(bgColor)
        val canvas = Canvas(finalBitmap)
        val paint = Paint().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                blendMode = BlendMode.MULTIPLY
            } else {
                @Suppress("DEPRECATION")
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }
        canvas.drawBitmap(baseBitmap, 0f, 0f, paint)
        return finalBitmap
    }

    private fun toPdfQuad(quad: Quad): PdfQuad {
        return PdfQuad(
            ulX = quad.ul_x,
            ulY = quad.ul_y,
            urX = quad.ur_x,
            urY = quad.ur_y,
            llX = quad.ll_x,
            llY = quad.ll_y,
            lrX = quad.lr_x,
            lrY = quad.lr_y
        )
    }
}