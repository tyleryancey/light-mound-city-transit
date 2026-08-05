@file:OptIn(ExperimentalSerializationApi::class)

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File

// ---------------------------------------------------------------------------
// GTFS-Realtime v2.0, mapped to Kotlin.
//
// Two rules learned from the library's source before writing this:
//   1. Every property needs a DEFAULT. kotlinx treats "optional" as "has a Kotlin
//      default value"; a field the feed omits and the class does not default will
//      throw MissingFieldException. Metro's feed omits most fields most of the time.
//   2. Enums map by ORDINAL unless every entry carries @ProtoNumber. GTFS-RT's Cause
//      starts at 1, so the naive mapping is off by one. Modelled as Int here; case 5
//      tests the enum path separately.
// ---------------------------------------------------------------------------

@Serializable
data class FeedMessage(
    @ProtoNumber(1) val header: FeedHeader = FeedHeader(),
    @ProtoNumber(2) val entity: List<FeedEntity> = emptyList(),
)

@Serializable
data class FeedHeader(
    @ProtoNumber(1) val gtfsRealtimeVersion: String = "",
    @ProtoNumber(2) val incrementality: Int? = null,
    @ProtoNumber(3) val timestamp: Long? = null,
)

@Serializable
data class FeedEntity(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val isDeleted: Boolean? = null,
    @ProtoNumber(3) val tripUpdate: TripUpdate? = null,
    @ProtoNumber(4) val vehicle: VehiclePosition? = null,
    @ProtoNumber(5) val alert: Alert? = null,
)

@Serializable
data class TripDescriptor(
    @ProtoNumber(1) val tripId: String? = null,
    @ProtoNumber(2) val startTime: String? = null,
    @ProtoNumber(3) val startDate: String? = null,
    @ProtoNumber(4) val scheduleRelationship: Int? = null,
    @ProtoNumber(5) val routeId: String? = null,
    @ProtoNumber(6) val directionId: Int? = null,
)

@Serializable
data class VehicleDescriptor(
    @ProtoNumber(1) val id: String? = null,
    @ProtoNumber(2) val label: String? = null,
    @ProtoNumber(3) val licensePlate: String? = null,
)

@Serializable
data class Position(
    @ProtoNumber(1) val latitude: Float = 0f,
    @ProtoNumber(2) val longitude: Float = 0f,
    @ProtoNumber(3) val bearing: Float? = null,
    @ProtoNumber(4) val odometer: Double? = null,
    @ProtoNumber(5) val speed: Float? = null,
)

// delay is int32 and SIGNED. -300 arrives as a 10-byte sign-extended varint.
@Serializable
data class StopTimeEvent(
    @ProtoNumber(1) val delay: Int? = null,
    @ProtoNumber(2) val time: Long? = null,
    @ProtoNumber(3) val uncertainty: Int? = null,
)

@Serializable
data class StopTimeUpdate(
    @ProtoNumber(1) val stopSequence: Int? = null,
    @ProtoNumber(2) val arrival: StopTimeEvent? = null,
    @ProtoNumber(3) val departure: StopTimeEvent? = null,
    @ProtoNumber(4) val stopId: String? = null,
    @ProtoNumber(5) val scheduleRelationship: Int? = null,
)

@Serializable
data class TripUpdate(
    @ProtoNumber(1) val trip: TripDescriptor = TripDescriptor(),
    @ProtoNumber(2) val stopTimeUpdate: List<StopTimeUpdate> = emptyList(),
    @ProtoNumber(3) val vehicle: VehicleDescriptor? = null,
    @ProtoNumber(4) val timestamp: Long? = null,
    @ProtoNumber(5) val delay: Int? = null,
)

@Serializable
data class VehiclePosition(
    @ProtoNumber(1) val trip: TripDescriptor? = null,
    @ProtoNumber(2) val position: Position? = null,
    @ProtoNumber(3) val currentStopSequence: Int? = null,
    @ProtoNumber(4) val currentStatus: Int? = null,
    @ProtoNumber(5) val timestamp: Long? = null,
    @ProtoNumber(6) val congestionLevel: Int? = null,
    @ProtoNumber(7) val stopId: String? = null,
    @ProtoNumber(8) val vehicle: VehicleDescriptor? = null,
    @ProtoNumber(9) val occupancyStatus: Int? = null,
)

@Serializable
data class Translation(
    @ProtoNumber(1) val text: String = "",
    @ProtoNumber(2) val language: String? = null,
)

@Serializable
data class TranslatedString(
    @ProtoNumber(1) val translation: List<Translation> = emptyList(),
)

@Serializable
data class TimeRange(
    @ProtoNumber(1) val start: Long? = null,
    @ProtoNumber(2) val end: Long? = null,
)

@Serializable
data class EntitySelector(
    @ProtoNumber(1) val agencyId: String? = null,
    @ProtoNumber(2) val routeId: String? = null,
    @ProtoNumber(3) val routeType: Int? = null,
    @ProtoNumber(4) val trip: TripDescriptor? = null,
    @ProtoNumber(5) val stopId: String? = null,
    @ProtoNumber(6) val directionId: Int? = null,
)

@Serializable
data class Alert(
    @ProtoNumber(1) val activePeriod: List<TimeRange> = emptyList(),
    @ProtoNumber(5) val informedEntity: List<EntitySelector> = emptyList(),
    @ProtoNumber(6) val cause: Int? = null,
    @ProtoNumber(7) val effect: Int? = null,
    @ProtoNumber(8) val url: TranslatedString? = null,
    @ProtoNumber(10) val headerText: TranslatedString? = null,
    @ProtoNumber(11) val descriptionText: TranslatedString? = null,
)

// Case 5 only: does kotlinx map enums by proto number or by declaration ordinal?
// GTFS-RT Cause starts at 1, so an ordinal mapping is off by one.
@Serializable
enum class Cause {
    @ProtoNumber(1) UNKNOWN_CAUSE,
    @ProtoNumber(2) OTHER_CAUSE,
    @ProtoNumber(10) CONSTRUCTION,
}

@Serializable
data class AlertCauseOnly(@ProtoNumber(6) val cause: Cause? = null)

// ---------------------------------------------------------------------------

private var passed = 0
private var failed = 0

private fun check(name: String, detail: String = "", body: () -> Boolean) {
    val result = runCatching(body)
    when {
        result.isSuccess && result.getOrThrow() -> { passed++; println("  PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
        result.isSuccess -> { failed++; println("  FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
        else -> { failed++; println("  THREW $name -> ${result.exceptionOrNull()}") }
    }
}

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Length-delimited wrapper: field [num], payload [body]. */
private fun wrap(num: Int, body: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    writeVarint(out, (num.toLong() shl 3) or 2L)
    writeVarint(out, body.size.toLong())
    out.write(body)
    return out.toByteArray()
}

private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
    var v = value
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v != 0L) out.write(b or 0x80) else { out.write(b); return }
    }
}

private fun varint(num: Int, value: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    writeVarint(out, (num.toLong() shl 3))
    writeVarint(out, value)
    return out.toByteArray()
}

fun main() {
    println("kotlinx-serialization-protobuf probe")
    println("=".repeat(72))

    // -- Case 1: negative int32 as a 10-byte sign-extended varint ------------
    // StopTimeEvent { delay = -300 }. These exact bytes occur 116x in the real feed.
    println("\n[1] negative int32, 10-byte sign-extended varint")
    val negDelay = hex("08d4fdffffffffffffff01")
    check("delay == -300", "wire d4fdffffffffffffff01") {
        ProtoBuf.decodeFromByteArray<StopTimeEvent>(negDelay).delay == -300
    }
    for ((v, h) in listOf(
        -1 to "ffffffffffffffffff01",
        -60 to "c4ffffffffffffffff01",
        0 to "00",
        60 to "3c",
        1200 to "b009",
    )) {
        check("delay == $v", "wire $h") {
            ProtoBuf.decodeFromByteArray<StopTimeEvent>(hex("08$h")).delay == v
        }
    }

    // -- Case 2: unknown field in the extension range ------------------------
    println("\n[2] unknown field in the GTFS-RT extension range 1000-1999")
    check("field 1001 (varint) skipped, known field still read") {
        val b = hex("08d4fdffffffffffffff01") + varint(1001, 42L)
        ProtoBuf.decodeFromByteArray<StopTimeEvent>(b).delay == -300
    }

    // -- Case 3: unknown fields of every wire type ---------------------------
    println("\n[3] unknown fields, each wire type")
    val known = hex("08d4fdffffffffffffff01")
    check("wire 0 varint")        { ProtoBuf.decodeFromByteArray<StopTimeEvent>(known + varint(900, 7L)).delay == -300 }
    check("wire 2 len-delimited") { ProtoBuf.decodeFromByteArray<StopTimeEvent>(known + wrap(901, "hello".toByteArray())).delay == -300 }
    check("wire 5 fixed32")       { ProtoBuf.decodeFromByteArray<StopTimeEvent>(known + hex("6d") + hex("0000803f")).delay == -300 }
    check("wire 1 fixed64")       { ProtoBuf.decodeFromByteArray<StopTimeEvent>(known + hex("71") + hex("0000000000000000")).delay == -300 }
    check("nested msg with an unknown subfield") {
        val stu = wrap(3, known + varint(1002, 1L)) + wrap(4, "12345".toByteArray())
        val d = ProtoBuf.decodeFromByteArray<StopTimeUpdate>(stu)
        d.departure?.delay == -300 && d.stopId == "12345"
    }

    // -- Case 4: absent optional fields --------------------------------------
    println("\n[4] absent optional fields (the real feed omits most of them)")
    check("empty message decodes")               { ProtoBuf.decodeFromByteArray<StopTimeEvent>(ByteArray(0)).delay == null }
    check("time present, delay absent")          { val e = ProtoBuf.decodeFromByteArray<StopTimeEvent>(hex("10") + hex("b8b6c9c206")); e.delay == null && e.time != null }
    check("TripUpdate with no stop_time_update") { ProtoBuf.decodeFromByteArray<TripUpdate>(wrap(1, wrap(1, "3407211".toByteArray()))).stopTimeUpdate.isEmpty() }
    check("Position without bearing or speed")   { val p = ProtoBuf.decodeFromByteArray<Position>(hex("0d") + hex("a4174d42") + hex("15") + hex("d3adb4c2")); p.bearing == null && p.speed == null }

    // -- Case 5: enum mapping -------------------------------------------------
    println("\n[5] enum by proto number vs declaration ordinal")
    check("cause 10 -> CONSTRUCTION (number, not ordinal)") {
        ProtoBuf.decodeFromByteArray<AlertCauseOnly>(varint(6, 10L)).cause == Cause.CONSTRUCTION
    }
    check("cause 2 -> OTHER_CAUSE") {
        ProtoBuf.decodeFromByteArray<AlertCauseOnly>(varint(6, 2L)).cause == Cause.OTHER_CAUSE
    }
    // Informational, NOT pass/fail: kotlinx is expected to THROW on an enum number
    // with no @ProtoNumber match — which is exactly why the production models type
    // every GTFS-RT enum as Int? instead of a Kotlin enum class.
    val unknownEnum = runCatching { ProtoBuf.decodeFromByteArray<AlertCauseOnly>(varint(6, 99L)) }
    println("  INFO  unknown enum number 99 -> " +
        (unknownEnum.exceptionOrNull()?.let { "throws ${it::class.simpleName} (expected; model enums as Int?)" }
            ?: "decodes to ${unknownEnum.getOrNull()}"))

    // -- Case 6: the real files ----------------------------------------------
    println("\n[6] the three captured feeds")
    val dir = File(System.getProperty("fixtures") ?: "../fixtures")
    fun load(n: String) = File(dir, n).takeIf { it.isFile }?.readBytes()

    val trips = load("StlRealTimeTrips.pb")
    val vehicles = load("StlRealTimeVehicles.pb")
    val alerts = load("StlRealTimeAlerts.pb")

    if (trips == null || vehicles == null || alerts == null) {
        println("  SKIP  fixtures not found in ${dir.absolutePath}")
        println("        put the three .pb files there, or pass -Dfixtures=/path")
    } else {
        val t = ProtoBuf.decodeFromByteArray<FeedMessage>(trips)
        val v = ProtoBuf.decodeFromByteArray<FeedMessage>(vehicles)
        val a = ProtoBuf.decodeFromByteArray<FeedMessage>(alerts)

        check("trips: 153 entities", "got ${t.entity.size}") { t.entity.size == 153 }
        check("trips: header 2.0 / ts 1785775752") { t.header.gtfsRealtimeVersion == "2.0" && t.header.timestamp == 1785775752L }
        check("trips: 127 with stop_time_update") { t.entity.count { (it.tripUpdate?.stopTimeUpdate?.size ?: 0) > 0 } == 127 }
        check("trips: 26 canceled (schedule_relationship 3)") { t.entity.count { it.tripUpdate?.trip?.scheduleRelationship == 3 } == 26 }
        check("trips: 9536 stop_time_updates") { t.entity.sumOf { it.tripUpdate?.stopTimeUpdate?.size ?: 0 } == 9536 }
        check("trips: TripUpdate.delay never set") { t.entity.all { it.tripUpdate?.delay == null } }
        check("trips: arrival never set") { t.entity.all { e -> e.tripUpdate?.stopTimeUpdate?.all { it.arrival == null } ?: true } }
        check("trips: stop_sequence never set") { t.entity.all { e -> e.tripUpdate?.stopTimeUpdate?.all { it.stopSequence == null } ?: true } }

        val delays = t.entity.flatMap { it.tripUpdate?.stopTimeUpdate ?: emptyList() }.mapNotNull { it.departure?.delay }
        check("trips: 8453 delay values", "got ${delays.size}") { delays.size == 8453 }
        check("trips: 2054 negative (24.3%)", "got ${delays.count { it < 0 }}") { delays.count { it < 0 } == 2054 }
        check("trips: delay range -300..1200", "got ${delays.minOrNull()}..${delays.maxOrNull()}") { delays.minOrNull() == -300 && delays.maxOrNull() == 1200 }
        check("trips: every delay a whole minute") { delays.all { it % 60 == 0 } }
        check("trips: delay constant within each trip") {
            t.entity.mapNotNull { it.tripUpdate }.filter { it.stopTimeUpdate.isNotEmpty() }
                .all { tu -> tu.stopTimeUpdate.map { it.departure?.delay }.distinct().size == 1 }
        }

        check("vehicles: 127 entities", "got ${v.entity.size}") { v.entity.size == 127 }
        check("vehicles: no bearing, speed, stop_id, current_stop_sequence") {
            v.entity.mapNotNull { it.vehicle }.all {
                it.position?.bearing == null && it.position?.speed == null &&
                    it.stopId == null && it.currentStopSequence == null
            }
        }
        check("vehicles: zero rail (19731B/R, 19870B/R)") {
            v.entity.mapNotNull { it.vehicle?.trip?.routeId }.none { it in setOf("19731B", "19731R", "19870B", "19870R") }
        }
        check("vehicles: label max length 42", "got ${v.entity.mapNotNull { it.vehicle?.vehicle?.label?.length }.maxOrNull()}") {
            v.entity.mapNotNull { it.vehicle?.vehicle?.label?.length }.maxOrNull() == 42
        }

        check("alerts: 24 entities", "got ${a.entity.size}") { a.entity.size == 24 }
        check("alerts: effect never set") { a.entity.all { it.alert?.effect == null } }
        check("alerts: 27 informed entities") { a.entity.sumOf { it.alert?.informedEntity?.size ?: 0 } == 27 }
        check("alerts: cause 19 OTHER + 5 CONSTRUCTION") {
            val c = a.entity.mapNotNull { it.alert?.cause }
            c.count { it == 2 } == 19 && c.count { it == 10 } == 5
        }
    }

    println("\n" + "=".repeat(72))
    println("passed $passed   failed $failed")
    println(
        if (failed == 0)
            "VERDICT: kotlinx-serialization-protobuf handles this feed. Either decoder is viable;\n" +
                "         choose on allocation cost and dependency count (docs/03-ARCHITECTURE.md §2)."
        else
            "VERDICT: at least one case failed. Use the hand-rolled reader and record the\n" +
                "         failing cases in CLAUDE.md."
    )
}
