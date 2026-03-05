package dev.rdh.bf

@OptIn(ExperimentalUnsignedTypes::class)
fun bfRun(program: Iterable<BFOperation>,
          stdin: BfInput,
          stdout: BfOutput,
) {
    runImpl(UByteArray(TAPE_SIZE), 0, program.toList(), stdin, stdout)
    stdout.flush()
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun runImpl(tape: UByteArray, pointer: Int,
                    program: List<BFOperation>,
                    stdin: BfInput, stdout: BfOutput): Int {
    var pointer = pointer

    for (op in program) {
        when (op) {
            is PointerMove -> pointer = pointer.wrappingAdd(op.value, TAPE_SIZE)
            is ValueChange -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = (tape[index].toInt() + op.value).toUByte()
            }
            is Print -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                stdout.writeByte(tape[index].toInt())
            }
            is Input -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = stdin.readByte().toUByte()
            }
            is Loop -> {
                while (tape[pointer].toInt() != 0) {
                    pointer = runImpl(tape, pointer, op, stdin, stdout)
                }
            }
            is SetToConstant -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = op.value
            }
            is Copy -> {
                val dest = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[dest] = (tape[dest].toInt() + tape[pointer].toInt() * op.multiplier).toUByte()
            }
        }
    }

    return pointer
}
