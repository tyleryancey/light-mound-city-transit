package moundcity.transit.core.query

import java.util.zip.ZipFile
import moundcity.transit.core.gtfs.FixturePaths
import moundcity.transit.core.gtfs.GtfsFeed
import moundcity.transit.core.gtfs.IndexWriter
import moundcity.transit.core.gtfs.ScheduleIndex
import moundcity.transit.core.rt.RtDecoder

/** Shared fixture products; built once per test run. */
object QueryTestData {
    val index: ScheduleIndex by lazy {
        val feed = ZipFile(FixturePaths.gtfsZip).use { zip ->
            GtfsFeed.load { name -> zip.getInputStream(zip.getEntry(name)).bufferedReader() }
        }
        ScheduleIndex(IndexWriter.build(feed).container())
    }
    val rtTrips by lazy { RtDecoder.decodeTrips(FixturePaths.fixturesDir.resolve("StlRealTimeTrips.pb").readBytes()) }
    val rtVehicles by lazy { RtDecoder.decodeVehicles(FixturePaths.fixturesDir.resolve("StlRealTimeVehicles.pb").readBytes()) }
    val rtAlerts by lazy { RtDecoder.decodeAlerts(FixturePaths.fixturesDir.resolve("StlRealTimeAlerts.pb").readBytes()) }
}
