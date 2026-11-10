package com.seeandsay.chronologer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NarrationDao {
    @Query(
        """
        SELECT * FROM chronologer_history
        ORDER BY timestamp DESC, id DESC
        """,
    )
    fun getAllLogsSorted(): Flow<List<NarrationLog>>

    @Query(
        """
        SELECT * FROM chronologer_history
        WHERE primary_app LIKE '%' || :query || '%'
           OR primary_activity LIKE '%' || :query || '%'
           OR visible_text LIKE '%' || :query || '%'
        ORDER BY timestamp DESC, id DESC
        """,
    )
    fun searchLogs(query: String): Flow<List<NarrationLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: NarrationLog)

    @Query("DELETE FROM chronologer_history")
    suspend fun clearAllLogs()
}
