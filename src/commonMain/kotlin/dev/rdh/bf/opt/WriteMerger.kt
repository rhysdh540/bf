package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange

/**
 * Merges adjacent writes to the same cell when doing so is semantics-preserving.
 */
internal object WriteMerger : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size - 1) {
            val current = program[i]
            val next = program[i + 1]

            // set(x), add(y) => set(x + y)
            if (current is SetToConstant && next is ValueChange && current.offset == next.offset) {
                val value = (current.value.toInt() + next.value).toUByte()
                program[i] = SetToConstant(value = value, offset = current.offset)
                program.removeAt(i + 1)
                continue
            }

            // remove a pure write to a cell immediately followed by set(...) to the same cell:
            if (next is SetToConstant && current.overwritesCell(next.offset)) {
                program[i] = next
                program.removeAt(i + 1)
                continue
            }

            i++
        }
    }

    private fun BFOperation.overwritesCell(offset: Int): Boolean = when (this) {
        is SetToConstant -> this.offset == offset
        is ValueChange -> this.offset == offset
        is Copy -> this.offset == offset
        else -> false
    }
}
