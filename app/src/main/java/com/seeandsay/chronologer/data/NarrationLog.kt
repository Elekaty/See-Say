package com.seeandsay.chronologer.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chronologer_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["primary_app"]),
        Index(value = ["primary_activity"]),
        Index(value = ["visible_text"]),
    ],
)
data class NarrationLog(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Int = 0,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "formatted_timestamp")
    val formattedTimestamp: String,
    @ColumnInfo(name = "active_device")
    val activeDevice: String,
    @ColumnInfo(name = "primary_app")
    val primaryApp: String,
    @ColumnInfo(name = "primary_activity")
    val primaryActivity: String,
    @ColumnInfo(name = "visible_text")
    val visibleText: String,
    @ColumnInfo(name = "micro_actions")
    val microActions: String,
    @ColumnInfo(name = "physical_action")
    val physicalAction: String,
    @ColumnInfo(name = "ambient_context")
    val ambientContext: String,
    @ColumnInfo(name = "raw_payload")
    val rawPayload: String,
)
