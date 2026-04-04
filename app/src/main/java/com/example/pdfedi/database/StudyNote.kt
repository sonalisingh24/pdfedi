package com.example.pdfedi.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "study_notes")
data class StudyNote(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentUri: String, // To link the note to a specific PDF
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val textContent: String
)