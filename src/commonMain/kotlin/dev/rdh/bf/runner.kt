package dev.rdh.bf

fun interface BfExecutable {
    fun run(input: BfInput, output: BfOutput)
}

fun interface BfRunner {
    fun compile(program: Iterable<BfBlockOp>, tapeSize: Int): BfExecutable

    fun run(program: Iterable<BfBlockOp>, tapeSize: Int, input: BfInput, output: BfOutput) {
        compile(program, tapeSize).run(input, output)
    }
}

