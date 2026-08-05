package moundcity.transit.core.rt

import java.io.ByteArrayOutputStream
import moundcity.transit.core.gtfs.FixturePaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Build plan 1.16 (unknown-field tolerance) and 1.17 (truncation safety).
 * 1.16 synthesizes a feed carrying extension fields in 1000–1999 at three
 * nesting levels; they must be skipped, never thrown. 1.17 sweeps prefixes of
 * every fixture: each either decodes or throws RtDecodeException — never any
 * other error, never a half-applied result.
 */
class RtRobustnessTest {

    // --- minimal protobuf builder (test-only) ---

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            val b = (x and 0x7F).toInt()
            x = x ushr 7
            if (x == 0L) { out.write(b); break }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    private fun field(number: Int, wireType: Int, payload: ByteArray): ByteArray =
        varint(((number shl 3) or wireType).toLong()) + payload

    private fun lenField(number: Int, payload: ByteArray): ByteArray =
        field(number, 2, varint(payload.size.toLong()) + payload)

    private fun strField(number: Int, s: String): ByteArray = lenField(number, s.toByteArray())

    @Test
    fun extensionFieldsAreSkippedNotThrown() {
        val ste = field(1, 0, varint(120))                            // delay = 120
        val stu = lenField(3, ste) + strField(4, "10624") +
            field(1500, 0, varint(7))                                 // extension varint inside STU
        val tripDesc = strField(1, "3407211")
        val tripUpdate = lenField(1, tripDesc) + lenField(2, stu) +
            lenField(1001, "ignored-extension".toByteArray())         // extension message inside TripUpdate
        val entity = strField(1, "e1") + lenField(3, tripUpdate)
        val header = strField(1, "2.0") + field(3, 0, varint(1_785_775_752))
        val feed = lenField(1, header) + lenField(2, entity) +
            field(1999, 0, varint(42))                                // extension at top level

        val decoded = RtDecoder.decodeTrips(feed)
        assertEquals(1, decoded.entities.size, "the one real entity survives the three extension fields")
        assertEquals(120, decoded.delayByTrip().getValue(3407211), "payload fields around the extensions decode intact")
    }

    @Test
    fun wireTypeMismatchIsSkippedAsUnknownNotMisparsed() {
        // Review finding (Phase 1c): dispatch was field-number-only. A delay
        // (STE field 1, varint) arriving length-delimited had its LENGTH byte
        // read as the value — delay = 5 from garbage. Canonical protobuf (and
        // the oracle's library) treats a wire-type mismatch as an unknown
        // field: skip by the ACTUAL wire type.
        val bogusSte = lenField(1, ByteArray(5) { 0x08 })             // delay sent as bytes, not varint
        val stu = lenField(3, bogusSte) + strField(4, "10624")
        val tripUpdate = lenField(1, strField(1, "3407211")) + lenField(2, stu)
        val entity = strField(1, "e1") + lenField(3, tripUpdate)
        val header = strField(1, "2.0") + field(3, 0, varint(1_785_775_752))
        val feed = lenField(1, header) + lenField(2, entity)

        val decoded = RtDecoder.decodeTrips(feed)
        assertEquals(
            mapOf(3407211 to null),
            decoded.delayByTrip(),
            "the mismatched delay is an unknown field, so the trip reads as on-time — never delay=5-from-a-length-byte",
        )
    }

    @Test
    fun entityWithoutVehiclePayloadIsSkippedAndTripsTheShapeTripwire() {
        // Review finding (Phase 1c): decodeVehicles fabricated a (0,0) fix with
        // an empty tripId for an entity with no vehicle field. Skip it, and
        // record the anomaly on the existing forbidden-fields channel so the
        // fixture test absentFieldsStayAbsent trips the day the feed changes.
        val header = strField(1, "2.0") + field(3, 0, varint(1_785_775_752))
        val feed = lenField(1, header) + lenField(2, strField(1, "e1"))
        val decoded = RtDecoder.decodeVehicles(feed)
        assertEquals(emptyList(), decoded.fixes, "no phantom (0,0) fix")
        assertEquals(setOf("entity_without_vehicle"), decoded.forbiddenFieldsSeen, "the anomaly is recorded, not invented into data")
    }

    @Test
    fun everyPrefixOfEveryFixtureDecodesOrThrowsCleanly() {
        var throws = 0
        var successes = 0
        fun sweep(name: String, decode: (ByteArray) -> Any, stride: Int) {
            val bytes = FixturePaths.fixturesDir.resolve(name).readBytes()
            var n = 0
            while (n < bytes.size) {
                try {
                    decode(bytes.copyOf(n))
                    successes++
                } catch (e: RtDecodeException) {
                    throws++
                } catch (e: Throwable) {
                    fail("$name prefix $n threw ${e::class.simpleName}: ${e.message} — only RtDecodeException is clean")
                }
                n += if (n < 2000 || n > bytes.size - 2000) 1 else stride
            }
            decode(bytes) // and the full file still decodes
        }
        sweep("StlRealTimeVehicles.pb", RtDecoder::decodeVehicles, stride = 1)
        sweep("StlRealTimeAlerts.pb", RtDecoder::decodeAlerts, stride = 1)
        sweep("StlRealTimeTrips.pb", RtDecoder::decodeTrips, stride = 97)
        assertTrue(throws > 1000, "the sweep must actually exercise the failure path; saw $throws throws")
        assertTrue(successes > 100, "prefixes ending on entity boundaries decode as shorter feeds; saw $successes")
    }
}
