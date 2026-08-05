package moundcity.transit.data

import java.io.File
import moundcity.transit.core.gtfs.FixturePaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Build plan 2.5: newest valid filesDir index, else the asset; atomic write
 * via temp + fsync + rename. A half-written index can never be observed.
 */
class IndexStoreTest {

    private val goodBytes: ByteArray by lazy {
        FixturePaths.fixturesDir.resolve("index/index.bin").readBytes()
    }

    private fun tempDir(): File = File.createTempFile("idx", "").let {
        it.delete(); it.mkdirs(); it
    }

    @Test
    fun emptyDirFallsBackToTheAsset() {
        val store = IndexStore(tempDir()) { goodBytes }
        val loaded = store.load()
        assertNull(loaded.name, "the asset has no file name")
        assertEquals(5118, loaded.index.stopCount, "the asset index opens")
    }

    @Test
    fun newestValidFileWinsOverAssetAndOlderFiles() {
        val dir = tempDir()
        val store = IndexStore(dir) { goodBytes }
        store.writeAtomically("index-20260805.bin", goodBytes)
        store.writeAtomically("index-20260812.bin", goodBytes)
        assertEquals("index-20260812.bin", store.newestValidName(), "lexicographically newest date-stamped name")
        assertEquals("index-20260812.bin", store.load().name, "and load() picks it")
    }

    @Test
    fun corruptFileIsSkippedNotFatal() {
        val dir = tempDir()
        val store = IndexStore(dir) { goodBytes }
        store.writeAtomically("index-20260805.bin", goodBytes)
        File(dir, "index-20260812.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals("index-20260805.bin", store.newestValidName(), "the newer-but-corrupt file is skipped")
        assertEquals("index-20260805.bin", store.load().name, "load survives corruption by falling back")
    }

    @Test
    fun everythingCorruptFallsBackToAsset() {
        val dir = tempDir()
        val store = IndexStore(dir) { goodBytes }
        File(dir, "index-20260805.bin").writeBytes(ByteArray(64))
        assertNull(store.load().name, "asset again when nothing on disk validates")
    }

    @Test
    fun leftoverTempFileFromACrashIsInvisible() {
        val dir = tempDir()
        val store = IndexStore(dir) { goodBytes }
        File(dir, "index-20260812.bin.tmp").writeBytes(goodBytes)
        assertNull(store.newestValidName(), "a .tmp never counts — the rename is the commit point")
    }

    @Test
    fun atomicWriteLeavesNoTempBehind() {
        val dir = tempDir()
        val store = IndexStore(dir) { goodBytes }
        store.writeAtomically("index-20260812.bin", goodBytes)
        assertTrue(File(dir, "index-20260812.bin").isFile, "the final name exists")
        assertEquals(
            emptyList(),
            dir.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") },
            "no temp residue after a clean write",
        )
    }
}
