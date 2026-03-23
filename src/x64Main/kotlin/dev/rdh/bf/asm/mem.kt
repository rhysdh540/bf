package dev.rdh.bf.asm

import kotlin.math.abs

class Memory(
    val base: Register? = null,
    val index: Register? = null,
    val scale: Int = 1,
    val displacement: Long = 0,

    val size: UInt = 8u, // how wide is the memory access?
) : DataSource, DataDestination {
    val width: UInt

    init {
        require(scale in arrayOf(1, 2, 4, 8)) { "scale must be 1, 2, 4, or 8" }
        require(size in arrayOf(1u, 2u, 4u, 8u)) { "size must be 1, 2, 4, or 8 bytes" }
        require(base == null || index == null || base.width == index.width) { "base and index must have the same width" }

        width = when {
            base != null -> base.width
            index != null -> index.width
            else -> 64u // default to 64-bit addressing if no registers are used
        }
    }

    override fun toString() = buildString {
        append('[')
        if (base != null) append(base)
        if (index != null) {
            if (base != null) append('+')
            append(index)
            if (scale != 1) append("*$scale")
        }
        if (displacement != 0L) {
            val sign = if (displacement < 0) '-' else '+'
            append(sign).append(abs(displacement))
        }
        append(']')
    }
}