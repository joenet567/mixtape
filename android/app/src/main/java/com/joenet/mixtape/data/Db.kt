package com.joenet.mixtape.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/*
 * Mixtape's own data. Songs are referred to by their stable key (the YouTube video id, "yt:<id>",
 * or the content URI for files without one): MediaStore ids change when a file is rescanned,
 * video ids don't, so history, likes and tapes survive re-syncs.
 */

/** One listen: logged once a song has played 30 s or half its length, whichever comes first. */
@Entity(tableName = "plays", indices = [Index("key"), Index("playedAt")])
data class Play(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val title: String,
    val artist: String,
    val folder: String,
    val durationMs: Long,
    /** Where it was played from, e.g. "folder:music/chill/" (see PlayCtx). */
    val ctxRef: String?,
    val playedAt: Long,
    /** Plays less than 30 minutes apart share a session (used by radio's "played together"). */
    val session: Long,
)

/** Where you left a tape: "Jump back in" resumes from here. */
@Entity(tableName = "resume")
data class Resume(
    @PrimaryKey val ctxRef: String,
    val title: String,
    val lastKey: String,
    val positionMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "likes")
data class Like(
    @PrimaryKey val key: String,
    val title: String,
    val artist: String,
    val likedAt: Long,
)

/** A tape you recorded yourself. */
@Entity(tableName = "tapes")
data class UserTape(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into Tape.labels. */
    val color: Int,
    /** Opt-in: split into Side A and Side B like a real cassette. */
    val twoSided: Boolean = false,
    /** Cassette length in minutes when two-sided: 60 (C-60) or 90 (C-90). */
    val length: Int = 90,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "tape_tracks",
    primaryKeys = ["tapeId", "position"],
    foreignKeys = [ForeignKey(entity = UserTape::class, parentColumns = ["id"], childColumns = ["tapeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tapeId")],
)
data class TapeTrack(
    val tapeId: Long,
    val position: Int,
    val key: String,
    /** 0 = side A, 1 = side B (only meaningful on two-sided tapes). */
    val side: Int = 0,
    val title: String,
    val artist: String,
)

data class KeyCount(val key: String, val plays: Int, val last: Long)
data class ArtistCount(val artist: String, val plays: Int)

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(play: Play): Long

    @Query("SELECT * FROM plays ORDER BY playedAt DESC LIMIT 1")
    suspend fun last(): Play?

    @Query("SELECT * FROM plays ORDER BY playedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Play>>

    @Query("SELECT COUNT(*) FROM plays")
    fun count(): Flow<Int>

    /** Most played since [since], at least [min] plays. */
    @Query("SELECT key, COUNT(*) AS plays, MAX(playedAt) AS last FROM plays WHERE playedAt >= :since GROUP BY key HAVING COUNT(*) >= :min ORDER BY plays DESC, last DESC LIMIT :limit")
    suspend fun top(since: Long, min: Int, limit: Int): List<KeyCount>

    /** Played at least [min] times overall, but not since [before]. */
    @Query("SELECT key, COUNT(*) AS plays, MAX(playedAt) AS last FROM plays GROUP BY key HAVING COUNT(*) >= :min AND MAX(playedAt) < :before ORDER BY plays DESC LIMIT :limit")
    suspend fun forgotten(min: Int, before: Long, limit: Int): List<KeyCount>

    @Query("SELECT artist, COUNT(*) AS plays FROM plays WHERE playedAt >= :since GROUP BY artist ORDER BY plays DESC LIMIT :limit")
    suspend fun topArtists(since: Long, limit: Int): List<ArtistCount>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM plays WHERE playedAt >= :since AND playedAt < :until")
    suspend fun listenedMs(since: Long, until: Long): Long

    @Query("SELECT COUNT(*) FROM plays WHERE playedAt >= :since AND playedAt < :until")
    suspend fun playsBetween(since: Long, until: Long): Int

    @Query("SELECT key, COUNT(*) AS plays, MAX(playedAt) AS last FROM plays WHERE playedAt >= :since AND playedAt < :until GROUP BY key ORDER BY plays DESC, last DESC LIMIT :limit")
    suspend fun topBetween(since: Long, until: Long, limit: Int): List<KeyCount>

    @Query("SELECT artist, COUNT(*) AS plays FROM plays WHERE playedAt >= :since AND playedAt < :until GROUP BY artist ORDER BY plays DESC LIMIT :limit")
    suspend fun topArtistsBetween(since: Long, until: Long, limit: Int): List<ArtistCount>

    /** Songs played in the same sessions as [key] (radio's "you often play these together"). */
    @Query("SELECT p2.`key` AS `key`, COUNT(*) AS plays, MAX(p2.playedAt) AS last FROM plays p1 JOIN plays p2 ON p1.session = p2.session AND p2.`key` != p1.`key` WHERE p1.`key` = :key GROUP BY p2.`key` ORDER BY plays DESC LIMIT :limit")
    suspend fun playedWith(key: String, limit: Int): List<KeyCount>

    @Query("SELECT key, COUNT(*) AS plays, MAX(playedAt) AS last FROM plays GROUP BY key")
    suspend fun allCounts(): List<KeyCount>

    @Query("SELECT DISTINCT key FROM plays WHERE playedAt >= :since")
    suspend fun playedSince(since: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveResume(resume: Resume)

    @Query("SELECT * FROM resume ORDER BY updatedAt DESC LIMIT :limit")
    fun resumes(limit: Int): Flow<List<Resume>>

    @Query("SELECT * FROM resume ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun resumeList(limit: Int): List<Resume>
}

@Dao
interface LikeDao {
    @Query("SELECT * FROM likes ORDER BY likedAt DESC")
    fun all(): Flow<List<Like>>

    @Query("SELECT key FROM likes ORDER BY likedAt DESC")
    suspend fun keys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(like: Like)

    @Query("DELETE FROM likes WHERE key = :key")
    suspend fun unlike(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE key = :key)")
    suspend fun isLiked(key: String): Boolean
}

data class TapeWithTracks(val tape: UserTape, val tracks: List<TapeTrack>)

@Dao
interface TapeDao {
    @Query("SELECT * FROM tapes ORDER BY updatedAt DESC")
    fun tapes(): Flow<List<UserTape>>

    @Query("SELECT * FROM tape_tracks ORDER BY tapeId, position")
    fun tracks(): Flow<List<TapeTrack>>

    @Query("SELECT * FROM tapes ORDER BY updatedAt DESC")
    suspend fun tapeList(): List<UserTape>

    @Query("SELECT * FROM tape_tracks ORDER BY tapeId, side, position")
    suspend fun trackList(): List<TapeTrack>

    @Insert
    suspend fun insert(tape: UserTape): Long

    @androidx.room.Update
    suspend fun update(tape: UserTape)

    @Query("DELETE FROM tapes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM tapes WHERE id = :id")
    suspend fun tape(id: Long): UserTape?

    @Query("SELECT * FROM tape_tracks WHERE tapeId = :id ORDER BY position")
    suspend fun tracksOf(id: Long): List<TapeTrack>

    @Query("DELETE FROM tape_tracks WHERE tapeId = :id")
    suspend fun clearTracks(id: Long)

    @Insert
    suspend fun insertTracks(tracks: List<TapeTrack>)

    @Query("UPDATE tapes SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** Replace a tape's track list (positions renumbered in order). */
    @Transaction
    suspend fun setTracks(id: Long, tracks: List<TapeTrack>) {
        clearTracks(id)
        insertTracks(tracks.mapIndexed { i, t -> t.copy(tapeId = id, position = i) })
        touch(id, System.currentTimeMillis())
    }
}

@Database(entities = [Play::class, Resume::class, Like::class, UserTape::class, TapeTrack::class], version = 1, exportSchema = false)
abstract class MixtapeDb : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun likes(): LikeDao
    abstract fun tapes(): TapeDao

    companion object {
        @Volatile private var instance: MixtapeDb? = null

        fun get(context: Context): MixtapeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MixtapeDb::class.java, "mixtape.db")
                .build()
                .also { instance = it }
        }
    }
}
