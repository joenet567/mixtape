package com.joenet.mixtape

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Pulls songs from the PC importer's sync server (`sync.bat`, or its tray icon) into the phone's
 * Music/<playlist>/ folders. Only files the phone doesn't already have are downloaded, matched by
 * the YouTube video id the importer puts in every file name ("Title [dQw4w9WgXcQ].mp3"). Lyrics
 * and loudness come along for every song, old ones included (see Sidecar). Nothing is ever deleted
 * from the phone.
 *
 * The same pull runs from the Sync screen and, unattended, from [SyncWorker].
 */
object SyncManager {

    const val DISCOVERY_PORT = 47810
    const val DEFAULT_PORT = 47811
    private const val DISCOVER_MSG = "MIXTAPE_DISCOVER"
    private val ID_RE = Regex("""\[([A-Za-z0-9_-]{11})]\.mp3$""", RegexOption.IGNORE_CASE)

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Working(
            val message: String,
            val done: Int = 0,
            val total: Int = 0,
            val fileFraction: Float = 0f,
        ) : State
        data class Finished(val added: Int, val alreadyHad: Int, val failed: List<String>, val lyrics: Int = 0) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true

    /** One pull at a time, whether you started it or the background worker did. */
    private val pulling = Mutex()

    fun savedAddress(context: Context): String =
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).getString("address", "").orEmpty()

    private fun saveAddress(context: Context, address: String) =
        context.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("address", address).apply()

    fun reset() {
        if (!busy) _state.value = State.Idle
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** Broadcasts on the LAN for a running sync.bat; calls [onFound] with "ip:port". */
    fun findPc(context: Context, onFound: (String) -> Unit) {
        if (busy) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Searching
            val found = runCatching { discover(app) }.getOrNull()
            if (found == null) {
                _state.value = State.Failed(
                    "No PC found. Is sync.bat running, and are the phone and PC on the same Wi-Fi? " +
                        "You can also type the address it prints."
                )
            } else {
                saveAddress(app, found)
                _state.value = State.Idle
                withContext(Dispatchers.Main) { onFound(found) }
            }
        }
    }

    fun sync(context: Context, rawAddress: String, onChanged: () -> Unit) {
        if (busy) return
        val app = context.applicationContext
        val address = normalize(rawAddress)
        if (address.isEmpty()) {
            _state.value = State.Failed("Enter the PC address shown by sync.bat, or tap Find PC.")
            return
        }
        saveAddress(app, address)
        job = scope.launch {
            try {
                _state.value = State.Working("Waiting for the background sync to finish…")
                val result = pulling.withLock { pull(app, address) { _state.value = it } }
                _state.value = result
                AutoSync.schedule(app) // now that a PC address is known, keep new songs coming
            } catch (e: CancellationException) {
                throw e
            } catch (e: org.json.JSONException) {
                _state.value = State.Failed("$address didn't answer like the Mixtape sync server.")
            } catch (e: Exception) {
                _state.value = State.Failed("Couldn't reach $address: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { onChanged() }
            }
        }
    }

    /**
     * The unattended pull for [SyncWorker]: the saved PC address first, then a 'Find PC' broadcast
     * in case the PC got a new address. Null when the PC isn't around (or a sync is already running).
     */
    suspend fun autoSync(context: Context): State.Finished? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!pulling.tryLock()) return@withContext null
        try {
            val saved = savedAddress(app)
            if (saved.isNotEmpty()) {
                try {
                    return@withContext pull(app, saved) {}
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // PC off, or it moved: look for it below
                }
            }
            val found = discover(app)?.takeIf { it != saved } ?: return@withContext null
            saveAddress(app, found)
            try {
                pull(app, found) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } finally {
            pulling.unlock()
        }
    }

    /** Downloads what's new, plus lyrics and loudness for everything; throws if the PC can't be reached. */
    private suspend fun pull(app: Context, address: String, progress: (State.Working) -> Unit): State.Finished {
        val startedAt = System.currentTimeMillis()
        progress(State.Working("Asking $address for the song list…"))
        val lib = JSONObject(httpGetText("http://$address/api/library"))
        val have = existingSongs(app)
        val todo = ArrayList<Pair<String, JSONObject>>() // folder to track
        val lyricsTodo = ArrayList<Pair<String, JSONObject>>()
        val gains = HashMap<String, Float>()
        var alreadyHad = 0
        val playlists = lib.getJSONArray("playlists")
        for (i in 0 until playlists.length()) {
            val pl = playlists.getJSONObject(i)
            val folder = pl.getString("name")
            val tracks = pl.getJSONArray("tracks")
            for (j in 0 until tracks.length()) {
                val t = tracks.getJSONObject(j)
                val id = t.optString("id")
                val key = keyOf(relativePathFor(folder), id)
                if (key in have) alreadyHad++ else todo += folder to t
                if (!Sidecar.isVideoId(id)) continue
                if (t.has("gain")) gains[id] = t.getDouble("gain").toFloat()
                if (t.has("lrc") && Sidecar.lyricsFile(app, id).length() != t.getLong("lrc")) lyricsTodo += folder to t
            }
        }
        // Loudness first: songs already on the phone even out from the next play on.
        Sidecar.saveGains(app, gains)

        val failed = ArrayList<String>()
        var added = 0
        todo.forEachIndexed { n, (folder, t) ->
            coroutineContext.ensureActive()
            val title = t.optString("title", t.getString("file"))
            progress(State.Working(title, n, todo.size))
            try {
                download(app, address, folder, t) { f -> progress(State.Working(title, n, todo.size, f)) }
                added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "$title (${e.message ?: e.javaClass.simpleName})"
            }
        }
        var lyrics = 0
        lyricsTodo.forEachIndexed { n, (folder, t) ->
            coroutineContext.ensureActive()
            progress(State.Working("Lyrics: ${t.optString("title")}", n, lyricsTodo.size))
            val lrc = t.getString("file").replace(Regex("""\.mp3$""", RegexOption.IGNORE_CASE), ".lrc")
            val ok = runCatching {
                val text = httpGetText("http://$address/files/${enc(folder)}/${enc(lrc)}")
                val target = Sidecar.lyricsFile(app, t.getString("id"))
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.writeText(text)
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (ok) lyrics++
        }
        // Home's "New from your PC" shows what the last sync brought in
        if (added > 0) {
            app.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putLong("lastAddedAt", startedAt).apply()
        }
        return State.Finished(added, alreadyHad, failed, lyrics)
    }

    private fun normalize(raw: String): String {
        val s = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (s.isEmpty()) return ""
        return if (':' in s) s else "$s:$DEFAULT_PORT"
    }

    private fun relativePathFor(folder: String) = "${Environment.DIRECTORY_MUSIC}/$folder/"

    private fun keyOf(relativePath: String, videoId: String) = "${relativePath.lowercase()}|$videoId"

    /** "music/playlist/|videoId" for every importer-made file already on the phone. */
    private fun existingSongs(context: Context): Set<String> {
        val out = HashSet<String>()
        context.contentResolver.query(
            MusicLibrary.collection,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH),
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MUSIC}/%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = ID_RE.find(c.getString(0).orEmpty())?.groupValues?.get(1) ?: continue
                out += keyOf(c.getString(1).orEmpty(), id)
            }
        }
        return out
    }

    private suspend fun download(
        context: Context,
        address: String,
        folder: String,
        track: JSONObject,
        progress: (Float) -> Unit,
    ) {
        val file = track.getString("file")
        val url = "http://$address/files/${enc(folder)}/${enc(file)}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 20000
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, file)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePathFor(folder))
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val target = resolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IOException("phone storage refused the file")
            try {
                resolver.openOutputStream(target)!!.use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (total > 0 && copied - lastReport > 256 * 1024) {
                                lastReport = copied
                                progress(copied.toFloat() / total)
                            }
                        }
                        if (total > 0 && copied != total) throw IOException("connection dropped")
                    }
                }
                // Clearing IS_PENDING publishes the file; MediaStore then reads its tags.
                resolver.update(target, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                resolver.delete(target, null, null)
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 20000
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun discover(context: Context): String? = withContext(Dispatchers.IO) {
        val targets = buildList {
            add(InetAddress.getByName("255.255.255.255"))
            addAll(subnetBroadcasts(context))
        }
        val msg = DISCOVER_MSG.toByteArray()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 800
                repeat(4) {
                    for (t in targets) runCatching { socket.send(DatagramPacket(msg, msg.size, t, DISCOVERY_PORT)) }
                    val buf = ByteArray(512)
                    val reply = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(reply)
                        val parts = String(reply.data, 0, reply.length).trim().split(' ')
                        if (parts.size >= 2 && parts[0] == "MIXTAPE") {
                            return@withContext "${reply.address.hostAddress}:${parts[1]}"
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }
        null
    }

    /** Directed broadcast (e.g. 192.168.1.255) for the current network; some routers drop 255.255.255.255. */
    private fun subnetBroadcasts(context: Context): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return props.linkAddresses.mapNotNull { la ->
            val addr = la.address as? Inet4Address ?: return@mapNotNull null
            val prefix = la.prefixLength
            if (prefix !in 1..30) return@mapNotNull null
            val ip = ByteBuffer.wrap(addr.address).int
            val mask = -1 shl (32 - prefix)
            InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
        }
    }
}
