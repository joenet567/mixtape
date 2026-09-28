package com.joenet.mixtape

/** A tape the app can open: a synced folder, one you recorded yourself, or Liked songs. */
sealed interface TapeRef {
    data class Folder(val key: String) : TapeRef
    data class User(val id: Long) : TapeRef
    data object Liked : TapeRef
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
