package bf.opt.strip

import bf.BFOperation
import bf.PointerMove
import bf.ValueChange
import bf.opt.OptimisationPass

/**
 * Removes [ValueChange] and [PointerMove] operations that have a value of 0.
 */
object ZeroRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        program.removeAll {
            it is ValueChange && it.value == 0
                    || it is PointerMove && it.value == 0
        }
    }
}