package moundcity.transit.data

import java.io.File
import java.io.FileOutputStream
import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * Picks the newest valid on-disk index, falling back to the bundled asset
 * (build plan 2.5). Writes go to a temp name, fsync, then atomic rename —
 * the rename is the commit point, so a half-written index is never visible.
 * Retention is keep-1 (review decision, Phase 2): the asset is the terminal
 * fallback and Phase 4's refresh validates before it ever writes, so
 * superseded indexes and crash-orphaned temps are pruned on the next
 * successful write. Pure JVM: the asset arrives as a lambda so the Android
 * glue stays thin; readFile is a test seam.
 */
class IndexStore(
    private val dir: File,
    private val asset: () -> ByteArray,
    private val readFile: (File) -> ByteArray = { it.readBytes() },
) {

    data class Loaded(val name: String?, val index: ScheduleIndex)

    private fun candidates(): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("index-") && it.name.endsWith(".bin") }
            .sortedByDescending { it.name }

    /** Newest by name among index-*.bin files that actually parse; null if none. */
    fun newestValidName(): String? = load().name

    /** One read and one parse per candidate, newest first (review finding, Phase 2). */
    fun load(): Loaded {
        for (file in candidates()) {
            val index = runCatching { ScheduleIndex(readFile(file)) }.getOrNull()
            if (index != null) return Loaded(file.name, index)
        }
        return Loaded(null, ScheduleIndex(asset()))
    }

    fun writeAtomically(name: String, bytes: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("atomic rename to ${target.name} failed")
        }
        // Prune AFTER the rename: directory metadata journals in order, so a
        // power cut can never persist these deletes while losing the rename.
        dir.listFiles()?.forEach { f ->
            val stale = f.name.endsWith(".tmp") ||
                (f.name.startsWith("index-") && f.name.endsWith(".bin") && f.name != target.name)
            if (stale) f.delete()
        }
    }
}
