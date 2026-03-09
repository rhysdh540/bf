package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.Loop
import dev.rdh.bf.PointerMove
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange
import dev.rdh.bf.defaultMap

/**
 * Optimization pass that replaces linear byte loops with [Copy] operations.
 *
 * A replaceable loop is one that:
 * 1. Applies only [PointerMove] and [ValueChange]
 * 2. Moves to other cells and increments/decrements them by some amount
 * 3. Returns to the original position
 * 4. Changes the induction cell (offset 0) by an odd amount each iteration
 *
 * For example, the loop `[->++>>+<<]` copies the current value:
 * - 2 times to the cell at offset +1
 * - 1 time to the cell at offset +3
 *
 * This gets replaced with `Copy(2, 1), Copy(1, 3), SetToConstant()`.
 *
 * The odd-induction requirement is the generalized part: in byte arithmetic,
 * odd deltas are invertible modulo 256, so the loop trip count can be folded
 * into constant multipliers.
 */
internal object CopyLoopReplacer : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size) {
            val op = program[i]
            if (op is Loop) {
                val replacement = analyzeCopyLoop(op)
                if (replacement != null) {
                    program.removeAt(i)
                    program.addAll(i, replacement)
                    i += replacement.size
                    continue
                }
            }
            i++
        }
    }

    private fun analyzeCopyLoop(loop: Loop): List<BFOperation>? {
        if (loop.isEmpty()) return null

        var currentOffset = 0
        val offsetChanges = defaultMap<Int, Int> { 0 }

        for (op in loop) {
            when (op) {
                is PointerMove -> currentOffset += op.value
                is ValueChange -> {
                    val targetOffset = currentOffset + op.offset
                    offsetChanges[targetOffset] += op.value
                }
                else -> return null
            }
        }

        // Must return to original position
        if (currentOffset != 0) return null

        val currentCellChange = offsetChanges.remove(0) ?: return null

        // In mod256 arithmetic, only odd deltas are invertible
        val inv = invertOdd(currentCellChange) ?: return null
        val eliminationScale = -inv and 0xFF

        val replacements = mutableListOf<BFOperation>()
        for ((offset, change) in offsetChanges) {
            if (change != 0) {
                val multiplier = forceByte(change * (eliminationScale and 0xFF))
                if (multiplier != 0) {
                    replacements += Copy(multiplier = multiplier, offset = offset)
                }
            }
        }

        replacements += SetToConstant.Default
        return replacements
    }

    private fun forceByte(value: Int): Int {
        return if (value >= 128) value - 256 else value
    }

    private fun invertOdd(value: Int): Int? {
        val normalized = value and 0xFF
        if (normalized == 0 || normalized % 2 == 0) return null

        for (candidate in 1..255 step 2) {
            if (normalized * (candidate and 0xFF) == 1) {
                return candidate
            }
        }

        return null
    }
}
