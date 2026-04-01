package com.example.dayprogress.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "day_history")
data class DayHistory(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val startTime: Long,
    val endTime: Long,
    val wasManual: Boolean
)

@Dao
interface DayHistoryDao {
    @Query("SELECT * FROM day_history ORDER BY date DESC LIMIT 30")
    fun getRecentHistory(): Flow<List<DayHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: DayHistory)
}

@Database(entities = [DayHistory::class], version = 1, exportSchema = false)
abstract class DayDatabase : RoomDatabase() {
    abstract fun dayHistoryDao(): DayHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: DayDatabase? = null

        fun getDatabase(context: android.content.Context): DayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DayDatabase::class.java,
                    "day_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
