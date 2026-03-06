package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation

/**
 * Represents some form of modification to a program. Any optimisations should NOT change the behavior of the program.
 */
internal interface OptimisationPass {

    /**
     * Modifies the given list of operations.
     */
    fun run(program: MutableList<BFOperation>)
}

fun bfOptimise(program: Iterable<BFOperation>, iterations: Int = 5): List<BFOperation> {
    val program = program.toMutableList()

    val corePasses = arrayOf(
        RunLengthMerger, ConstantReplacer, OffsetAdder, CopyLoopReplacer, WriteMerger
    )
    val cleanupPasses = arrayOf(
        DeadStartRemover,
        DeadEndRemover,
        ConsecutiveLoopRemover,
        ZeroRemover
    )

    repeat(iterations) {
        corePasses.forEach {
            it.run(program)
        }

        LoopOptimiser(*corePasses).run(program)
    }

    cleanupPasses.forEach {
        it.run(program)
    }

    LoopOptimiser(ConsecutiveLoopRemover, ZeroRemover).run(program)

    return program
}
