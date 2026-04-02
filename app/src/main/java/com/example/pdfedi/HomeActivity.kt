package com.example.pdfedi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class HomeActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private val pdfFiles = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        listView = findViewById(R.id.pdfListView)

        // Open the Editor when a file is clicked
        listView.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("PDF_PATH", pdfFiles[position].absolutePath)
            startActivity(intent)
        }
    }

    // Checking permissions in onResume ensures it re-checks if you return from Settings
    override fun onResume() {
        super.onResume()
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            loadAllPdfs() // Permission is granted, load the files!
        }
    }

    private fun loadAllPdfs() {
        pdfFiles.clear()

        // BULLETPROOF QUERY: Look for the .pdf extension instead of trusting Android's MIME tags
        val projection = arrayOf(MediaStore.Files.FileColumns.DATA)
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%.pdf")

        try {
            val cursor = contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, selectionArgs, null
            )

            cursor?.use {
                val dataIndex = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (it.moveToNext()) {
                    val path = it.getString(dataIndex)
                    if (path != null) {
                        pdfFiles.add(File(path))
                    }
                }
            }

            // DIAGNOSTIC CHECK: Tell us exactly what the engine found!
            if (pdfFiles.isEmpty()) {
                Toast.makeText(this, "0 PDFs found on the device!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Found ${pdfFiles.size} PDFs", Toast.LENGTH_SHORT).show()
            }

            // Bind the data to our custom black-text layout
            val adapter = ArrayAdapter(this, R.layout.item_pdf_list, pdfFiles.map { it.name })
            listView.adapter = adapter

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Scanner Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}