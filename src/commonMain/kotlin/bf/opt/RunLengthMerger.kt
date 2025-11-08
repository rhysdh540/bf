package bf.opt

import bf.BFOperation
import bf.PointerMove
import bf.ValueChange

/**
 * Merges consecutive [PointerMove] and [ValueChange] operations into a single operation.
 */
internal object RunLengthMerger : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size - 1) {
            val current = program[i]
            val next = program[i + 1]

            if (current is PointerMove && next is PointerMove) {
                program[i] = PointerMove(current.value + next.value)
                program.removeAt(i + 1)
            } else if (current is ValueChange && next is ValueChange && current.offset == next.offset) {
                program[i] = ValueChange(current.value + next.value, current.offset)
                program.removeAt(i + 1)
            } else {
                i++
            }
        }
    }
}