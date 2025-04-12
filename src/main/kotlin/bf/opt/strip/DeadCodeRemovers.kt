package bf.opt.strip

import bf.BFOperation
import bf.Loop
import bf.PointerMove
import bf.SetToZero
import bf.ValueChange
import bf.opt.OptimisationPass

/**
 * Optimisation pass that removes dead code at the start of the program.
 * Dead code is guaranteed to never run.
 * This includes loops and set-to-zero operations.
 */
internal object DeadStartRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.first() is Loop || program.first() is SetToZero) {
            program.removeFirst()
        }
    }
}

/**
 * Optimisation pass that removes necessary code at the end of the program.
 * Unnecessary code may change the internal state of the program but will have
 * no effect on the final output.
 * This includes pointer moves, value changes, and set-to-zero operations.
 */
internal object DeadEndRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.isNotEmpty() && program.last().let {
                it is PointerMove || it is ValueChange || it is SetToZero
            }) {
            program.removeAt(program.lastIndex)
        }
    }
}