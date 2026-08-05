package moundcity.transit.data

import java.io.File
import java.io.FileOutputStream
import moundcity.transit.core.gtfs.ScheduleIndex

/**
 * Picks the newest valid on-disk index, falling back to the bundled asset
 * (build plan 2.5). Writes go to a temp name, fsync, then atomic rename —
 * the rename is the commit point, so a half-written index is never visible.
 * Pure JVM: the asset arrives as a lambda so the Android glue stays thin.
 */
class IndexStore(private val dir: File, private val asset: () -> ByteArray) {

    data class Loaded(val name: String?, val index: ScheduleIndex)

    /** Newest by name among index-*.bin files that actually parse; null if none. */
    fun newestValidName(): String? =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("index-") && it.name.endsWith(".bin") }
            .sortedByDescending { it.name }
            .firstOrNull { runCatching { ScheduleIndex(it.readBytes()) }.isSuccess }
            ?.name

    fun load(): Loaded {
        val name = newestValidName()
        if (name != null) {
            val fromDisk = runCatching { ScheduleIndex(File(dir, name).readBytes()) }.getOrNull()
            if (fromDisk != null) return Loaded(name, fromDisk)
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
    }
}
