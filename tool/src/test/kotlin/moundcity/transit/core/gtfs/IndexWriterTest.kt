package moundcity.transit.core.gtfs

import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Build plan 1.7 + 1.9: the Kotlin writer must reproduce every section of the
 * Python-built index BYTE FOR BYTE — same input zip, same sha256 per section
 * (harness/fixtures/index/manifest.json, committed as the anchor). This is the
 * strongest single test in the project: it proves the port and the reference
 * implement the same format, not two formats that happen to agree on a query.
 */
class IndexWriterTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun loadFixtureFeed(): GtfsFeed = ZipFile(FixturePaths.gtfsZip).use { zip ->
        GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
    }

    private fun manifestEntries(): Map<String, Pair<Int, String>> {
        val text = FixturePaths.indexManifest.readText()
        val re = Regex(""""([a-z_.]+)":\s*\{\s*"bytes":\s*(\d+),\s*"sha256":\s*"([0-9a-f]{64})"""")
        return re.findAll(text).associate { m ->
            m.groupValues[1] to (m.groupValues[2].toInt() to m.groupValues[3])
        }
    }

    @Test
    fun everySectionMatchesThePythonManifestByteForByte() {
        val manifest = manifestEntries().filterKeys { it != "index.bin" }
        val built = IndexWriter.build(loadFixtureFeed())
        assertEquals(manifest.keys, built.sections.keys, "same ten sections, same names")
        for ((name, expected) in manifest) {
            val bytes = built.sections.getValue(name)
            assertEquals(expected.first, bytes.size, "section '$name' byte length")
            assertEquals(expected.second, sha256(bytes), "section '$name' sha256 — byte-for-byte identity")
        }
    }

    @Test
    fun containerMatchesThePythonContainerByteForByte() {
        val expected = manifestEntries().getValue("index.bin")
        val container = IndexWriter.build(loadFixtureFeed()).container()
        assertEquals(expected.first, container.size, "index.bin container byte length")
        assertEquals(expected.second, sha256(container), "index.bin container sha256 — both writers, one format")
    }

    @Test
    fun containerHoldsAllSectionsRecoverably() {
        val built = IndexWriter.build(loadFixtureFeed())
        val container = built.container()
        val reopened = IndexContainer.parse(container)
        assertEquals(built.sections.keys.toList(), reopened.keys.toList(), "section order preserved")
        for ((name, bytes) in built.sections) {
            assertEquals(sha256(bytes), sha256(reopened.getValue(name)), "section '$name' survives the container round-trip")
        }
    }
}
