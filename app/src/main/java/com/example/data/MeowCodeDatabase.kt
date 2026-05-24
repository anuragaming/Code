package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- entities ---

@Entity(tableName = "workspace_files")
data class WorkspaceFile(
    @PrimaryKey val path: String, // e.g., "Root/index.html"
    val parentPath: String,       // e.g., "Root"
    val name: String,             // e.g., "index.html"
    val isDirectory: Boolean,
    val content: String = "",
    val lastModified: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class MeowSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "remote_servers")
data class RemoteServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val ipAddress: String,
    val username: String,
    val port: Int = 22,
    val status: String = "Disconnected" // "Disconnected", "Connecting...", "Connected"
)

@Entity(tableName = "git_repos")
data class GitRepo(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val currentBranch: String = "main",
    val isCloned: Boolean = false,
    val remoteStatus: String = "Clean" // "Clean", "Conflict", "Behind", "Ahead"
)

@Entity(tableName = "extensions")
data class ExtensionItem(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val category: String, // "Theme", "Language", "AI", "Git"
    val isInstalled: Boolean = false,
    val rating: Float = 4.8f
)

// --- DAO ---

@Dao
interface MeowCodeDao {
    // Files
    @Query("SELECT * FROM workspace_files ORDER BY isDirectory DESC, path ASC")
    fun getAllWorkspaceFiles(): Flow<List<WorkspaceFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: WorkspaceFile)

    @Query("DELETE FROM workspace_files WHERE path = :path")
    suspend fun deleteFileByPath(path: String)

    @Query("DELETE FROM workspace_files WHERE path LIKE :folderPrefix || '%'")
    suspend fun deleteFilesUnderFolder(folderPrefix: String)

    // Settings
    @Query("SELECT * FROM settings")
    fun getAllSettings(): Flow<List<MeowSetting>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: MeowSetting)

    @Query("SELECT value FROM settings WHERE `key` = :key LIMIT 1")
    suspend fun getSettingValue(key: String): String?

    // Remote Servers
    @Query("SELECT * FROM remote_servers")
    fun getAllServers(): Flow<List<RemoteServer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: RemoteServer)

    @Query("DELETE FROM remote_servers WHERE id = :id")
    suspend fun deleteServerById(id: Int)

    // Git Repos
    @Query("SELECT * FROM git_repos")
    fun getAllGitRepos(): Flow<List<GitRepo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGitRepo(repo: GitRepo)

    @Query("DELETE FROM git_repos WHERE id = :id")
    suspend fun deleteGitRepoById(id: Int)

    // Extensions
    @Query("SELECT * FROM extensions")
    fun getAllExtensions(): Flow<List<ExtensionItem>>

    @Query("SELECT * FROM extensions WHERE id = :id LIMIT 1")
    suspend fun getExtensionById(id: String): ExtensionItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtension(extension: ExtensionItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtensions(extensions: List<ExtensionItem>)
}

// --- DATABASE ---

@Database(
    entities = [
        WorkspaceFile::class,
        MeowSetting::class,
        RemoteServer::class,
        GitRepo::class,
        ExtensionItem::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MeowCodeDatabase : RoomDatabase() {
    abstract fun dao(): MeowCodeDao

    companion object {
        @Volatile
        private var INSTANCE: MeowCodeDatabase? = null

        fun getDatabase(context: Context): MeowCodeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MeowCodeDatabase::class.java,
                    "meow_code_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
