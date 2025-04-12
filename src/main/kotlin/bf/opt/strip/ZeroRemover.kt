package bf.opt.strip

import bf.BFOperation
import bf.PointerMove
import bf.ValueChange
import bf.opt.OptimisationPass

object ZeroRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        program.removeAll {
            it is ValueChange && it.value == 0
                    || it is PointerMove && it.value == 0
        }
    }
}