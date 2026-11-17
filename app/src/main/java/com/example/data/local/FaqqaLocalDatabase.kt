package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM local_pages WHERE chapterId = :chapterId ORDER BY pageOrder ASC")
    fun observePagesForChapter(chapterId: String): Flow<List<LocalPageEntity>>

    @Query("SELECT * FROM local_pages WHERE chapterId = :chapterId ORDER BY pageOrder ASC")
    suspend fun getPagesForChapterOnce(chapterId: String): List<LocalPageEntity>

    @Query("SELECT * FROM local_pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageById(pageId: String): LocalPageEntity?

    @Query("SELECT * FROM local_pages WHERE projectId = :projectId ORDER BY pageOrder ASC")
    fun observePagesForProject(projectId: String): Flow<List<LocalPageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<LocalPageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: LocalPageEntity)

    @Query("DELETE FROM local_pages WHERE projectId = :projectId")
    suspend fun deletePagesForProject(projectId: String)

    @Query("DELETE FROM local_pages WHERE id = :pageId")
    suspend fun deletePageById(pageId: String)
}

@Dao
interface TextRegionDao {
    @Query("SELECT * FROM local_text_regions WHERE pageId = :pageId ORDER BY orderIndex ASC")
    fun observeRegionsForPage(pageId: String): Flow<List<LocalTextRegionEntity>>

    @Query("SELECT * FROM local_text_regions WHERE pageId = :pageId ORDER BY orderIndex ASC")
    suspend fun getRegionsForPageOnce(pageId: String): List<LocalTextRegionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegions(regions: List<LocalTextRegionEntity>)

    @Update
    suspend fun updateRegion(region: LocalTextRegionEntity)

    @Query("DELETE FROM local_text_regions WHERE pageId = :pageId")
    suspend fun deleteRegionsForPage(pageId: String)
}

@Dao
interface ProviderConfigDao {
    @Query("SELECT * FROM local_provider_configs ORDER BY providerId ASC")
    fun observeProviders(): Flow<List<LocalProviderConfigEntity>>

    @Query("SELECT * FROM local_provider_configs ORDER BY providerId ASC")
    suspend fun getProvidersOnce(): List<LocalProviderConfigEntity>

    @Query("SELECT * FROM local_provider_configs WHERE providerId = :providerId LIMIT 1")
    suspend fun getProvider(providerId: String): LocalProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(config: LocalProviderConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(configs: List<LocalProviderConfigEntity>)
}

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM local_browser_history ORDER BY visitedAtMillis DESC LIMIT 25")
    fun observeHistory(): Flow<List<LocalBrowserHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: LocalBrowserHistoryEntity)

    @Query("DELETE FROM local_browser_history")
    suspend fun clear()
}

@Database(
    entities = [
        LocalPageEntity::class,
        LocalTextRegionEntity::class,
        LocalProviderConfigEntity::class,
        LocalBrowserHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FaqqaLocalDatabase : RoomDatabase() {
    abstract fun pageDao(): PageDao
    abstract fun textRegionDao(): TextRegionDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun browserHistoryDao(): BrowserHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: FaqqaLocalDatabase? = null

        fun getInstance(context: Context): FaqqaLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FaqqaLocalDatabase::class.java,
                    "faqqa_local_studio.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
