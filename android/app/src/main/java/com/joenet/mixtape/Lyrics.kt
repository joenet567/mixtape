package com.joenet.mixtape

/** One line of lyrics; [timeMs] is when it's sung (0 for unsynced lyrics). */
data class LyricLine(val timeMs: Long, val text: String)

/** Lyrics from an .lrc file: timed ("[01:23.45] line") when [synced], otherwise plain text. */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    /** Index of the line being sung at [positionMs]; -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val TAG = Regex("""^\[([A-Za-z#]+):(.*)]$""")

        fun parse(text: String): Lyrics? {
            var offsetMs = 0L
            val timed = ArrayList<LyricLine>()
            val plain = ArrayList<String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                val tag = TAG.matchEntire(line)
                if (tag != null) {
                    if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                        offsetMs = tag.groupValues[2].trim().toLongOrNull() ?: 0L
                    }
                    continue
                }
                // A line can carry several stamps when it repeats: "[00:12.00][01:40.00] chorus"
                var rest = line
                val stamps = ArrayList<Long>()
                while (true) {
                    val m = STAMP.find(rest)?.takeIf { it.range.first == 0 } ?: break
                    val (min, sec, frac) = m.destructured
                    val fracMs = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.toLong()
                    }
                    stamps += min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs
                    rest = rest.substring(m.range.last + 1).trimStart()
                }
                if (stamps.isEmpty()) plain += line else stamps.forEach { timed += LyricLine(it, rest) }
            }
            if (timed.isNotEmpty()) {
                // A positive offset means the lyrics should show up sooner.
                return Lyrics(timed.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0)) }.sortedBy { it.timeMs }, synced = true)
            }
            val lines = plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
            return if (lines.isEmpty()) null else Lyrics(lines.map { LyricLine(0, it) }, synced = false)
        }
    }
}
