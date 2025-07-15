package bf.opt.strip

import bf.BFOperation
import bf.Copy
import bf.Loop
import bf.SetToConstant
import bf.opt.OptimisationPass

/**
 * Optimisation pass that removes consecutive loops and set-to-zero operations.
 * When exiting a loop, it is guaranteed that the current cell is zero.
 * So, any loops that immediately follow will never run and can be removed.
 */
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
        get() {
            if (this is Loop) return true
            if (this is SetToConstant && this.value.toInt() == 0 && this.offset == 0) return true
            if (this is Copy) return true
            return false
        }
}