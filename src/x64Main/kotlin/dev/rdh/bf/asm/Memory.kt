package dev.rdh.bf.asm

import kotlin.math.abs

class Memory private constructor(
    val base: Register?,
    val index: Register?,
    val scale: Int,
    val displacement: Long,
    val size: UInt
) : DataSource, DataDestination {
    init {
        require(scale in arrayOf(1, 2, 4, 8)) { "scale must be 1, 2, 4, or 8" }
        require(size in arrayOf(1u, 2u, 4u, 8u)) { "size must be 1, 2, 4, or 8 bytes" }
        require(base == null || index == null || base.width == index.width) { "base and index must have the same width" }
    }

    val width: UInt
        get() = size * Byte.SIZE_BITS.toUInt()

    override fun asString() = buildString {
        append(when (size) {
            1u -> "byte"
            2u -> "word"
            4u -> "dword"
            8u -> "qword"
            else -> error("invalid size")
        })
        append(" [")
        if (base != null) append(base.asString())
        if (index != null) {
            if (base != null) append('+')
            append(index.asString())
            if (scale != 1) append("*$scale")
        }
        if (displacement != 0L) {
            val sign = if (displacement < 0) '-' else '+'
            append(sign).append(abs(displacement))
        }
        append(']')
    }

    override fun toString() = asString()

    companion object {
        fun byte(base: Register? = null, index: Register? = null, scale: Int = 1, displacement: Long = 0) =
            Memory(base, index, scale, displacement, 1u)
        fun word(base: Register? = null, index: Register? = null, scale: Int = 1, displacement: Long = 0) =
            Memory(base, index, scale, displacement, 2u)
        fun dword(base: Register? = null, index: Register? = null, scale: Int = 1, displacement: Long = 0) =
            Memory(base, index, scale, displacement, 4u)
        fun qword(base: Register? = null, index: Register? = null, scale: Int = 1, displacement: Long = 0) =
            Memory(base, index, scale, displacement, 8u)
    }
}