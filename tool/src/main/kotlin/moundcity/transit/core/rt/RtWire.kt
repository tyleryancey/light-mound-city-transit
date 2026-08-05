package moundcity.transit.core.rt

/** A malformed or truncated realtime feed. Always this, never an index error —
 * a half-applied snapshot must be impossible (build plan 1.17). */
class RtDecodeException(message: String) : Exception(message)

/**
 * Protobuf wire reader over a byte slice (build plan 1.11, decision D4).
 * Handles the four wire types GTFS-RT uses; groups (3/4) throw. A negative
 * int32 arrives as a 10-byte sign-extended varint — `readVarint().toInt()`
 * takes the low 32 bits, which is the correct two's-complement truncation.
 */
class RtWire(private val bytes: ByteArray, private var pos: Int, private val end: Int) {

    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

    fun hasMore(): Boolean = pos < end

    fun readTag(): Int = readVarint().toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        var count = 0
        while (true) {
            if (pos >= end) throw RtDecodeException("truncated varint at byte $pos")
            if (count == 10) throw RtDecodeException("varint longer than 10 bytes at byte $pos")
            val b = bytes[pos].toInt()
            pos++
            count++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readFixed32(): Int {
        if (pos + 4 > end) throw RtDecodeException("truncated fixed32 at byte $pos")
        val v = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readFloat(): Float = Float.fromBits(readFixed32())

    /** Returns a sub-reader over a length-delimited payload and advances past it. */
    fun readLengthDelimited(): RtWire {
        val len = readVarint()
        if (len < 0 || pos + len > end) {
            throw RtDecodeException("length-delimited field of $len bytes overruns the buffer at byte $pos")
        }
        val sub = RtWire(bytes, pos, pos + len.toInt())
        pos += len.toInt()
        return sub
    }

    fun readString(): String {
        val sub = readLengthDelimited()
        return String(bytes, sub.pos, sub.end - sub.pos, Charsets.UTF_8)
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> {
                if (pos + 8 > end) throw RtDecodeException("truncated fixed64 at byte $pos")
                pos += 8
            }
            2 -> readLengthDelimited()
            5 -> readFixed32()
            else -> throw RtDecodeException("unsupported wire type $wireType (start/end group) at byte $pos")
        }
    }
}
