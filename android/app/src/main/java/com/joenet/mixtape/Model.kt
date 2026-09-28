package com.joenet.mixtape

/** A tape the app can open: a synced folder, one you recorded yourself, or Liked songs. */
sealed interface TapeRef {
    data class Folder(val key: String) : TapeRef
    data class User(val id: Long) : TapeRef
    data object Liked : TapeRef
}

/**
 * Where a queue came from. [name] is shown ("Next from: Chill"); [ref] lets the app rebuild the
 * same queue later ("Jump back in") and is null for one-offs like search results.
 */
data class PlayCtx(val name: String, val ref: String?) {
    companion object {
        fun of(ref: TapeRef, name: String) = PlayCtx(name, ref.token())
        fun artist(name: String) = PlayCtx(name, "artist:$name")
        val ALL = PlayCtx("All songs", "all")
        fun oneOff(name: String) = PlayCtx(name, null)
    }
}

fun TapeRef.token(): String = when (this) {
    is TapeRef.Folder -> "folder:$key"
    is TapeRef.User -> "user:$id"
    TapeRef.Liked -> "liked"
}

/** A context ref back to what it points at: a tape, an artist, or everything. */
sealed interface CtxTarget {
    data class OfTape(val ref: TapeRef) : CtxTarget
    data class OfArtist(val name: String) : CtxTarget
    data object Everything : CtxTarget

    companion object {
        fun parse(ref: String?): CtxTarget? = when {
            ref == null -> null
            ref == "all" -> Everything
            ref == "liked" -> OfTape(TapeRef.Liked)
            ref.startsWith("folder:") -> OfTape(TapeRef.Folder(ref.removePrefix("folder:")))
            ref.startsWith("user:") -> ref.removePrefix("user:").toLongOrNull()?.let { OfTape(TapeRef.User(it)) }
            ref.startsWith("artist:") -> OfArtist(ref.removePrefix("artist:"))
            else -> null
        }
    }
}

/** Bottom-bar destinations, drawn as the deck's function selector. */
enum class Tab(val legend: String) { Home("HOME"), Search("SEARCH"), Library("LIBRARY") }

/** Screens inside a tab. Each tab keeps its own back stack, like Spotify and YT Music. */
sealed interface Route {
    data object Root : Route
    data class Tape(val ref: TapeRef) : Route
    data class Artist(val name: String) : Route
    data object Sync : Route
    data object Settings : Route
    data object Rewind : Route
}
