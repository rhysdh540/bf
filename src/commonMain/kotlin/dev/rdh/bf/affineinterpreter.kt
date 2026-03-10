package dev.rdh.bf

object AffineInterpreterRunner : BfRunner {
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val lowered = bfLowerAffine(program.toList())
        return BfExecutable { i, o -> bfRunAffine(lowered, i, o) }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
fun bfRunAffine(program: List<BFAffineOp>, stdin: BfInput, stdout: BfOutput) {
    runAffineImpl(UByteArray(TAPE_SIZE), 0, program, stdin, stdout)
    stdout.flush()
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun runAffineImpl(tape: UByteArray, pointer: Int,
                          program: List<BFAffineOp>,
                          stdin: BfInput, stdout: BfOutput): Int {
    var pointer = pointer

    for (op in program) {
        when (op) {
            is BFAffineBlock -> {
                pointer += op.baseShift
                for (seg in op.segments) {
                    when (seg) {
                        is BFAffineInput -> {
                            tape[pointer] = stdin.readByte().toUByte()
                        }
                        is BFAffineOutput -> {
                            stdout.writeByte(tape[pointer].toInt())
                        }
                        is BFAffineWriteBatch -> {
                            val values = seg.writes
                                .flatMap { write -> write.expr.terms.map { it.offset } }
                                .distinct()
                                .associateWith { offset ->
                                    tape[pointer.wrappingAdd(offset, TAPE_SIZE)].toInt()
                                }
                            for (write in seg.writes) {
                                val expr = write.expr.constant + write.expr.terms.sumOf { term ->
                                    values[term.offset]!! * term.coefficient
                                }
                                tape[pointer.wrappingAdd(write.offset, TAPE_SIZE)] = expr.toUByte()
                            }
                        }
                    }
                }
                pointer += op.pointerDelta - op.baseShift
            }
            is BFAffineLoop -> {
                while (tape[pointer].toInt() != 0) {
                    pointer = runAffineImpl(tape, pointer, op, stdin, stdout)
                }
            }
        }
    }

    return pointer
}