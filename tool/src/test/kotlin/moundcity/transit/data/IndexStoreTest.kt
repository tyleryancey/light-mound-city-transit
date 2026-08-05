package moundcity.transit.data

import java.io.File
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
        // The committed asset (hash-pinned to the anchor by AssetsTest) — NOT
        // harness/fixtures/index/index.bin, which is gitignored and absent in CI.
        File(AssetPaths.assetsDir, "index.bin").readBytes()
    }

    private fun tempDir(): File = File.createTempFile("idx", "").let {
        it.delete(); it.mkdirs(); it
    }

    @Test
    fun emptyDirFallsBackToTheAsset() {
        val store = IndexStore(tempDir(), { goodBytes })
        val loaded = store.load()
        assertNull(loaded.name, "the asset has no file name")
        assertEquals(5118, loaded.index.stopCount, "the asset index opens")
    }

    @Test
    fun newestValidFileWinsOverAssetAndOlderFiles() {
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        store.writeAtomically("index-20260805.bin", goodBytes)
        store.writeAtomically("index-20260812.bin", goodBytes)
        assertEquals("index-20260812.bin", store.newestValidName(), "lexicographically newest date-stamped name")
        assertEquals("index-20260812.bin", store.load().name, "and load() picks it")
    }

    @Test
    fun corruptFileIsSkippedNotFatal() {
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        store.writeAtomically("index-20260805.bin", goodBytes)
        File(dir, "index-20260812.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals("index-20260805.bin", store.newestValidName(), "the newer-but-corrupt file is skipped")
        assertEquals("index-20260805.bin", store.load().name, "load survives corruption by falling back")
    }

    @Test
    fun everythingCorruptFallsBackToAsset() {
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        File(dir, "index-20260805.bin").writeBytes(ByteArray(64))
        assertNull(store.load().name, "asset again when nothing on disk validates")
    }

    @Test
    fun leftoverTempFileFromACrashIsInvisible() {
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        File(dir, "index-20260812.bin.tmp").writeBytes(goodBytes)
        assertNull(store.newestValidName(), "a .tmp never counts — the rename is the commit point")
    }

    @Test
    fun successfulWritePrunesSupersededIndexesAndStaleTemps() {
        // Review finding (Phase 2): nothing ever deleted superseded 3.3 MB
        // indexes or crash-orphaned .tmp files — unbounded growth before
        // Phase 4's refresh even exists. Retention choice: keep-1 (the asset
        // is the terminal fallback; Phase 4 validates before writing).
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        store.writeAtomically("index-20260805.bin", goodBytes)
        File(dir, "index-20260101.bin.tmp").writeBytes(ByteArray(16)) // crash residue
        store.writeAtomically("index-20260812.bin", goodBytes)
        assertEquals(
            listOf("index-20260812.bin"),
            dir.listFiles()!!.map { it.name }.sorted(),
            "exactly one live index; no superseded .bin, no .tmp residue",
        )
    }

    @Test
    fun loadReadsTheWinningIndexExactlyOnce() {
        // Review finding (Phase 2): newestValidName read+parsed every candidate,
        // then load() re-read and re-parsed the winner — double the 3.3 MB
        // startup cost. load() now scans newest-first, one read per candidate.
        val dir = tempDir()
        val reads = mutableMapOf<String, Int>()
        val store = IndexStore(dir, { goodBytes }) { f -> reads.merge(f.name, 1, Int::plus); f.readBytes() }
        store.writeAtomically("index-20260805.bin", goodBytes)
        assertEquals("index-20260805.bin", store.load().name, "the on-disk index wins")
        assertEquals(1, reads["index-20260805.bin"], "one read, one parse")
    }

    @Test
    fun atomicWriteLeavesNoTempBehind() {
        val dir = tempDir()
        val store = IndexStore(dir, { goodBytes })
        store.writeAtomically("index-20260812.bin", goodBytes)
        assertTrue(File(dir, "index-20260812.bin").isFile, "the final name exists")
        assertEquals(
            emptyList(),
            dir.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") },
            "no temp residue after a clean write",
        )
    }
}
