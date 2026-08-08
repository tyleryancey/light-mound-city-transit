package moundcity.transit.core.gtfs

import java.io.File
import java.io.Reader
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Locates harness/fixtures by walking up from the test working directory. */
object FixturePaths {
    val fixturesDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "harness/fixtures")
            if (File(candidate, "google_transit.zip").isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("harness/fixtures/google_transit.zip not found above ${File("").absolutePath}")
    }

    val gtfsZip: File get() = File(fixturesDir, "google_transit.zip")
    val indexManifest: File get() = File(fixturesDir, "index/manifest.json")
}

/** The idiom every fixture consumer needs: a by-name zip entry reader. */
fun zipOpener(zip: ZipFile): (String) -> Reader =
    { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }

/** The 489k-row fixture, parsed once per test run — not once per test class. */
val fixtureFeed: GtfsFeed by lazy {
    ZipFile(FixturePaths.gtfsZip).use { zip -> GtfsFeed.load(zipOpener(zip)) }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
