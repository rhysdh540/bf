package bf.opt.strip

import bf.BFOperation
import bf.Loop
import bf.SetToZero
import bf.opt.OptimisationPass

internal object ConsecutiveLoopRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size - 1) {
            if (program[i].isLoop && program[i + 1].isLoop) {
                program.removeAt(i + 1)
            } else {
                i++
            }
        }
    }

    private val BFOperation.isLoop: Boolean
        get() = this is Loop || this is SetToZero
}