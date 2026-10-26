package cn.studybridge.mobile

import java.io.File

/** AtomicFile requires caller-owned exclusion, including across store instances. */
internal object PrivateFileLocks {
    // Bounded for the process lifetime. A collision serializes unrelated IO;
    // unlike a path registry this does not retain every image ever opened.
    private val locks = Array(64) { Any() }

    fun <T> access(file: File, block: () -> T): T =
        synchronized(locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]) { block() }
}
