package moundcity.transit.core.rt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Build plan 1.11: the varint/wire reader against the six exact fixtures of
 * doc 01 §5b. A negative int32 is a 10-byte sign-extended varint; a decoder
 * that reads it unsigned puts the departure 584 billion years out.
 */
class RtWireTest {

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun varint(s: String): Long = RtWire(hex(s)).readVarint()

    @Test
    fun negative300IsTheTenByteFixture() {
        val v = varint("d4fdffffffffffffff01")
        assertEquals(-300, v.toInt(), "-300 decodes via low-32-bit truncation")
        assertEquals("18446744073709551316", v.toULong().toString(), "read unsigned, the same bytes are the 584-billion-year bug")
    }

    @Test
    fun remainingDocFixturesDecodeExactly() {
        assertEquals(-60, varint("c4ffffffffffffffff01").toInt(), "-60")
        assertEquals(-1, varint("ffffffffffffffffff01").toInt(), "-1")
        assertEquals(0, varint("00").toInt(), "0")
        assertEquals(60, varint("3c").toInt(), "60")
        assertEquals(1200, varint("b009").toInt(), "1200")
    }

    @Test
    fun truncatedVarintThrowsCleanly() {
        assertFailsWith<RtDecodeException>("EOF inside a varint must throw, not return garbage") {
            RtWire(hex("d4fdffff")).readVarint()
        }
    }

    @Test
    fun elevenByteVarintThrows() {
        assertFailsWith<RtDecodeException>("a varint never exceeds 10 bytes") {
            RtWire(hex("ffffffffffffffffffff01")).readVarint()
        }
    }

    @Test
    fun fixed32ReadsLittleEndianFloat() {
        // 38.6349f == 0x421A8A23 (verified via struct.pack); wire order is little-endian
        val w = RtWire(hex("238a1a42"))
        assertEquals(38.6349f, w.readFloat(), "Position lat/lon are LE fixed32 floats")
    }

    @Test
    fun skipHandlesTheFourRealWireTypesAndThrowsOnGroups() {
        // varint, fixed64, length-delimited, fixed32 — then a start-group tag
        val w = RtWire(hex("ac02" + "1122334455667788" + "03aabbcc" + "99aabbcc"))
        w.skip(0); w.skip(1); w.skip(2); w.skip(5)
        assertEquals(false, w.hasMore(), "all four payloads consumed")
        assertFailsWith<RtDecodeException>("wire types 3/4 (groups) never appear in GTFS-RT") {
            RtWire(hex("00")).skip(3)
        }
    }

    @Test
    fun lengthDelimitedRunningPastEndThrows() {
        assertFailsWith<RtDecodeException>("declared length past the buffer is truncation, not data") {
            RtWire(hex("05aabb")).skip(2)
        }
    }

    @Test
    fun tagSplitsFieldNumberAndWireType() {
        val w = RtWire(hex("08d4fdffffffffffffff01"))
        val tag = w.readTag()
        assertEquals(1, tag ushr 3, "field number 1 (StopTimeEvent.delay)")
        assertEquals(0, tag and 7, "wire type varint")
        assertEquals(-300, w.readVarint().toInt(), "the doc's full fixture: tag then value")
    }
}
