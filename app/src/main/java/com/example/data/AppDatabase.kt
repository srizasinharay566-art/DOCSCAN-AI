package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tags: String? = null,
    val summary: String? = null
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class Page(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val imagePath: String, // Path in local files dir
    val orderIndex: Int,
    val ocrText: String? = null,
    val filterApplied: String = "none", // none, grayscale, monochrome, sharpen, vintage, cleanup
    val contrastVal: Float = 1.0f,
    val brightnessVal: Float = 0.0f
)

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Int): Document?

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY orderIndex ASC")
    fun getPagesForDocumentFlow(docId: Int): Flow<List<Page>>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY orderIndex ASC")
    suspend fun getPagesForDocument(docId: Int): List<Page>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: Document): Long

    @Update
    suspend fun updateDocument(document: Document)

    @Delete
    suspend fun deleteDocument(document: Document)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: Page): Long

    @Update
    suspend fun updatePage(page: Page)

    @Delete
    suspend fun deletePage(page: Page)

    @Query("DELETE FROM pages WHERE documentId = :docId")
    suspend fun deletePagesForDocument(docId: Int)
}

@Database(entities = [Document::class, Page::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "docscan_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
