package bf.opt

import bf.BFOperation
import bf.PointerMove
import bf.ValueChange

internal object RunLengthMerger : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size) {
            val op = program[i]
            if (op is ValueChange) {
                var num = op.value

                val j = i + 1
                while (j < program.size && program[j] is ValueChange) {
                    num += (program[j] as ValueChange).value
                    program.removeAt(j)
                }

                num %= 256

                program[i] = ValueChange(num)
            }

            if (op is PointerMove) {
                var num = op.value

                val j = i + 1
                while (j < program.size && program[j] is PointerMove) {
                    num += (program[j] as PointerMove).value
                    program.removeAt(j)
                }

                num %= 256

                program[i] = PointerMove(num)
            }

            i++
        }
    }
}