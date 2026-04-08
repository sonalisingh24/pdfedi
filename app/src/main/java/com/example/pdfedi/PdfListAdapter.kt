package com.example.pdfedi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PdfFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val dateModifiedMs: Long
) {
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1) String.format(Locale.getDefault(), "%.1f MB", mb)
            else String.format(Locale.getDefault(), "%.1f KB", kb)
        }

    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dateModifiedMs))
}

class PdfListAdapter(
    private var pdfList: List<PdfFile>,
    private val onClick: (PdfFile) -> Unit
) : RecyclerView.Adapter<PdfListAdapter.ViewHolder>() {

    fun updateData(newList: List<PdfFile>) {
        pdfList = newList
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPdfName)
        val tvSize: TextView = view.findViewById(R.id.tvPdfSize)
        val tvDate: TextView = view.findViewById(R.id.tvPdfDate)

        val container: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pdf = pdfList[position]
        holder.tvName.text = pdf.name
        holder.tvSize.text = pdf.formattedSize
        holder.tvDate.text = pdf.formattedDate

        holder.container.setOnClickListener { onClick(pdf) }
    }

    override fun getItemCount(): Int = pdfList.size
}