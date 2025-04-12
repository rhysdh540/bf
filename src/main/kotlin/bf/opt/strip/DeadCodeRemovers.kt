package bf.opt.strip

import bf.BFOperation
import bf.Loop
import bf.PointerMove
import bf.SetToZero
import bf.ValueChange
import bf.opt.OptimisationPass

internal object DeadStartRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.first() is Loop || program.first() is SetToZero) {
            program.removeFirst()
        }
    }
}

internal object DeadEndRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.isNotEmpty() && program.last().let {
                it is PointerMove || it is ValueChange || it is SetToZero
            }) {
            program.removeAt(program.lastIndex)
        }
    }
}