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
    private lateinit var btnToggleDraw: Button
    private lateinit var pdfRecyclerView: ZoomableRecyclerView

    // === State Variables ===
    private var isDrawing = false
    private var pdfRenderer: PdfRenderer? = null
    private var cachedPdfFile: File? = null

    // === File Picker Launcher ===
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { loadPdfFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        // 2. Link UI variables to the XML layout
        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnToggleDraw = findViewById(R.id.btnToggleDraw)
        pdfRecyclerView = findViewById(R.id.pdfRecyclerView)

        // 3. Set up the RecyclerView to scroll vertically
        pdfRecyclerView.layoutManager = LinearLayoutManager(this)

        // 4. Open PDF Button Logic
        btnOpenPdf.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/pdf"))
        }

        // 5. Toggle Draw Button Logic (The Master Switch)
        btnToggleDraw.setOnClickListener {
            isDrawing = !isDrawing

            // Update the Button UI
            if (isDrawing) {
                btnToggleDraw.text = "Draw: ON"
                btnToggleDraw.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
            } else {
                btnToggleDraw.text = "Draw: OFF"
                btnToggleDraw.setBackgroundColor(Color.parseColor("#B0BEC5")) // Gray
            }

            // Tell the Zoom Engine to switch touch modes
            pdfRecyclerView.isDrawingMode = isDrawing

            // Tell the Adapter to update the drawing glass on all visible pages
            val adapter = pdfRecyclerView.adapter as? PdfPageAdapter
            if (adapter != null) {
                adapter.isDrawingMode = isDrawing
                adapter.notifyDataSetChanged() // Instantly refreshes the screen
            }
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
                // Clean up the old renderer if opening a new file
                pdfRenderer?.close()

                val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(fileDescriptor)

                // Pass the renderer to our Adapter
                val adapter = PdfPageAdapter(pdfRenderer!!, pdfRenderer!!.pageCount)

                // Make sure the adapter knows the current button state when loading a new file!
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