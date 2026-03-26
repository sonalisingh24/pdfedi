package com.example.pdfedi

import android.graphics.Bitmap
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
    var isDrawingMode = false

    // A Mutex ensures we only open and render one PDF page at a time to prevent crashes
    private val renderMutex = Mutex()

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val pageImageView: ImageView = itemView.findViewById(R.id.pageImageView)
        var renderJob: Job? = null // Track the background job so we can cancel it if the user scrolls too fast
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun getItemCount(): Int = pageCount

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Clear the old image while the new one loads
        holder.pageImageView.setImageBitmap(null)
        // NEW: Find the drawing view and turn it on or off based on our master state
        val drawView = holder.itemView.findViewById<CustomDrawView>(R.id.drawView)
        drawView.isDrawingEnabled = isDrawingMode

        // Cancel any previous rendering job on this specific view if it was recycled quickly
        holder.renderJob?.cancel()

        // Launch a coroutine to do the heavy rendering in the background
        holder.renderJob = CoroutineScope(Dispatchers.IO).launch {
            var bitmap: Bitmap? = null

            // Safely lock the renderer so no other scroll event can interrupt this math
            renderMutex.withLock {
                try {
                    val page = pdfRenderer.openPage(position)

                    // Create a bitmap (multiplying by 2.5 gives crisp text when zoomed)
                    bitmap = Bitmap.createBitmap(
                        (page.width * 2.5).toInt(),
                        (page.height * 2.5).toInt(),
                        Bitmap.Config.ARGB_8888
                    )

                    page.render(bitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Switch back to the Main UI thread to actually display the image
            withContext(Dispatchers.Main) {
                bitmap?.let {
                    holder.pageImageView.setImageBitmap(it)
                }
            }
        }
    }
}