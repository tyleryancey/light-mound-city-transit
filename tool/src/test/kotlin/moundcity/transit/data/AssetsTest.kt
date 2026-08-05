package moundcity.transit.data

import java.io.File
import java.security.MessageDigest
import moundcity.transit.core.gtfs.FixturePaths
import moundcity.transit.core.gtfs.ScheduleIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build plan 2.3: the bundled assets. index.bin must be the byte-identical
 * twin of the manifest-anchored build; the JSONs carry their capture dates
 * and the pipeline's exact bytes (fares/holidays sha256s are the
 * stl_bundle_* artifacts' own published hashes).
 */
class AssetsTest {

    private val assetsDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "tool/src/main/assets")
            if (File(candidate, "index.bin").isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("tool/src/main/assets not found above ${File("").absolutePath}")
    }

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun assetIndexIsTheManifestAnchoredBuild() {
        val manifest = FixturePaths.indexManifest.readText()
        val expected = Regex(""""index\.bin":\s*\{\s*"bytes":\s*(\d+),\s*"sha256":\s*"([0-9a-f]{64})"""")
            .find(manifest)!!.groupValues
        val asset = File(assetsDir, "index.bin")
        assertEquals(expected[1].toLong(), asset.length(), "asset index.bin byte length matches the anchor")
        assertEquals(expected[2], sha256(asset), "asset index.bin IS the anchored build, byte for byte")
    }

    @Test
    fun assetIndexActuallyLoads() {
        val index = ScheduleIndex(File(assetsDir, "index.bin").readBytes())
        assertEquals(5118, index.stopCount, "the bundled index opens and reads")
        assertTrue(index.resolveStop(10624) != null, "and resolves a real stop code")
    }

    @Test
    fun bundledJsonsAreThePipelinesBytesWithCaptureDates() {
        assertEquals(
            "4ae58d030f98ce7269c1e623a46850fa91ec8641472dcff1bb16da24fb6b329c",
            sha256(File(assetsDir, "fares.json")),
            "fares.json is the stl_bundle_fares artifact (as_of 2026-08-05), byte for byte",
        )
        assertEquals(
            "290da7ab70004df17482f9eb6c8cd6296ba923114cf8a5989d7bfa23cc47f213",
            sha256(File(assetsDir, "holidays.json")),
            "holidays.json is the stl_bundle_holidays artifact (2026, bus/rail distinct), byte for byte",
        )
        val fares = File(assetsDir, "fares.json").readText()
        assertTrue("\"as_of\": \"2026-08-05\"" in fares, "fares carry their capture date on screen (D8)")
        val contacts = File(assetsDir, "contacts.json").readText()
        assertTrue("\"capturedOn\": \"2026-08-04\"" in contacts, "contacts carry their capture date")
        assertTrue("314-289-6873" in contacts, "Metro Public Safety is present")
    }
}
