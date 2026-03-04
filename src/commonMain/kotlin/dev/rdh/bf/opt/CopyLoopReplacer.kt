package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.Loop
import dev.rdh.bf.PointerMove
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange
import dev.rdh.bf.defaultMap

/**
 * Optimization pass that replaces copy loops with [Copy] operations.
 *
 * A copy loop is a loop that:
 * 1. Decrements the current cell by 1 each iteration
 * 2. Moves to other cells and increments/decrements them by some amount
 * 3. Returns to the original position
 *
 * For example, the loop `[->++>>+<<]` copies the current value:
 * - 2 times to the cell at offset +1
 * - 1 time to the cell at offset +3
 *
 * This gets replaced with `Copy(2, 1), Copy(1, 3), SetToConstant()`.
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

        val currentCellChange = offsetChanges.remove(0)
        if (currentCellChange != -1) return null

        val replacements = mutableListOf<BFOperation>()
        for ((offset, change) in offsetChanges) {
            if (change != 0) {
                replacements += Copy(multiplier = change, offset = offset)
            }
        }

        // Must have at least one target to copy to
        if (replacements.isEmpty()) return null

        replacements += SetToConstant()
        return replacements
    }
}
