package com.example.pdfedi

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PdfPageAdapter(
    private val pdfRenderer: PdfRenderer,
    private val pageCount: Int
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    // THE NEW SETTINGS STATE
    var isDrawingMode = false
    var currentDrawColor = Color.parseColor("#F44336")
    var currentStrokeWidth = 8f
    var isEraser = false
    var isHighlighter = false

    private val renderMutex = Mutex()

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
        holder.pageImageView.setImageBitmap(null)

        // Apply all the current settings to this specific page's drawing glass
        holder.drawView.isDrawingEnabled = isDrawingMode
        holder.drawView.pageIndex = position
        holder.drawView.currentDrawColor = currentDrawColor
        holder.drawView.currentStrokeWidth = currentStrokeWidth
        holder.drawView.isEraser = isEraser
        holder.drawView.isHighlighter = isHighlighter

        holder.renderJob?.cancel()
        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var bitmap: Bitmap? = null
            renderMutex.withLock {
                try {
                    val page = pdfRenderer.openPage(position)
                    bitmap = Bitmap.createBitmap((page.width * 2.5).toInt(), (page.height * 2.5).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap?.eraseColor(Color.WHITE)
                    page.render(bitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                bitmap?.let { holder.pageImageView.setImageBitmap(it) }
            }
        }
    }
}