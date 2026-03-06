package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.PointerMove
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange

/**
 * Final peephole pass that merges the current operation with the previously emitted one.
 */
internal object OpMerger : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        val merged = mutableListOf<BFOperation>()

        for (op in program) {
            var current = op

            while (merged.isNotEmpty()) {
                val previous = merged.last()
                val combined = tryMerge(previous, current) ?: break
                merged.removeLast()
                current = combined
            }

            when (current) {
                is PointerMove -> if (current.value != 0) merged += current
                is ValueChange -> if (current.value != 0) merged += current
                else -> merged += current
            }
        }

        program.clear()
        program.addAll(merged)
    }

    private fun tryMerge(previous: BFOperation, current: BFOperation): BFOperation? {
        if (previous is PointerMove && current is PointerMove) {
            return PointerMove(previous.value + current.value)
        }

        if (previous is ValueChange && current is ValueChange && previous.offset == current.offset) {
            return ValueChange(previous.value + current.value, previous.offset)
        }

        if (previous is SetToConstant && current is ValueChange && previous.offset == current.offset) {
            val value = (previous.value.toInt() + current.value).toUByte()
            return SetToConstant(value = value, offset = previous.offset)
        }

        if (current is SetToConstant && previous.overwritesCell(current.offset)) {
            return current
        }

        return null
    }

    private fun BFOperation.overwritesCell(offset: Int): Boolean = when (this) {
        is SetToConstant -> this.offset == offset
        is ValueChange -> this.offset == offset
        is Copy -> this.offset == offset
        else -> false
    }
}
