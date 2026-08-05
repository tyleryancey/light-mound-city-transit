package moundcity.transit.core.gtfs

import java.io.File

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
