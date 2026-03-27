package com.example.pdfedi

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // === UI Variables ===
    private lateinit var btnOpenPdf: Button
    private lateinit var btnSavePdf: Button
    private lateinit var btnHand: Button
    private lateinit var btnMarker: Button
    private lateinit var pdfRecyclerView: ZoomableRecyclerView

    // === State Variables ===
    private var isDrawing = false // Starts in Hand Mode (false)
    private var pdfRenderer: PdfRenderer? = null
    private var cachedPdfFile: File? = null

    // === File Picker ===
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadPdfFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PDFBoxResourceLoader.init(applicationContext)

        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnHand = findViewById(R.id.btnHand)
        btnMarker = findViewById(R.id.btnMarker)
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)

        // THE PADLOCK: This totally disables native vertical scrolling when Marker is ON
        pdfRecyclerView.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean {
                return !isDrawing
            }
        }

        // --- BUTTON CLICKS ---

        btnOpenPdf.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }

        btnSavePdf.setOnClickListener {
            Toast.makeText(this, "Save functionality coming soon!", Toast.LENGTH_SHORT).show()
        }

        // 1. Hand Button (Scroll/Pan Mode)
        btnHand.setOnClickListener {
            isDrawing = false
            btnHand.setBackgroundColor(Color.parseColor("#4CAF50")) // Turn Hand Green
            btnMarker.setBackgroundColor(Color.parseColor("#B0BEC5")) // Turn Marker Gray
            updateModes()
        }

        // 2. Marker Button (Draw Mode)
        btnMarker.setOnClickListener {
            isDrawing = true
            btnMarker.setBackgroundColor(Color.parseColor("#4CAF50")) // Turn Marker Green
            btnHand.setBackgroundColor(Color.parseColor("#B0BEC5")) // Turn Hand Gray
            updateModes()
        }
    }

    // Tells the Engine and Adapter to switch states immediately
    // Tells the Engine and Adapter to switch states SILENTLY
    private fun updateModes() {
        // 1. Tell the Engine how to handle touches
        pdfRecyclerView.isDrawingMode = isDrawing

        // 2. Tell the Adapter to remember the mode for any future pages you scroll to
        val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
        if (adapter != null) {
            adapter.isDrawingMode = isDrawing
        }

        // 3. THE FIX: Silently update the pages already on the screen without reloading them!
        for (i in 0 until pdfRecyclerView.childCount) {
            val child = pdfRecyclerView.getChildAt(i)
            val drawView = child.findViewById<CustomDrawView>(R.id.drawView)
            drawView?.isDrawingEnabled = isDrawing
        }
    }
    private fun loadPdfFromUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val tempFile = File(cacheDir, "temp_working_pdf.pdf")
                val outputStream = FileOutputStream(tempFile)

                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()

                cachedPdfFile = tempFile

                withContext(Dispatchers.Main) {
                    displayPdf()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displayPdf() {
        cachedPdfFile?.let { file ->
            try {
                pdfRenderer?.close()
                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)

                val adapter = PdfPageAdapter(pdfRenderer!!, pdfRenderer!!.pageCount)
                adapter.isDrawingMode = isDrawing
                pdfRecyclerView.adapter = adapter
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
    }
}