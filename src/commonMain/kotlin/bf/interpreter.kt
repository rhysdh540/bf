@file:OptIn(ExperimentalUnsignedTypes::class)

package bf

fun bfRun(program: Iterable<BFOperation>,
          stdout: OutputConsumer,
          stdin: InputProvider = NullInputProvider
) {
    runImpl(UByteArray(TAPE_SIZE), 0, program.toList(), stdout, stdin)
    stdout.flush()
}

private fun runImpl(tape: UByteArray, pointer: Int,
                    program: List<BFOperation>,
                    stdout: OutputConsumer, stdin: InputProvider): Int {
    var pointer = pointer
    var insn = 0

    while (insn < program.size) {
        when (val op = program[insn]) {
            is PointerMove -> pointer = pointer.wrappingAdd(op.value, TAPE_SIZE)
            is ValueChange -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = (tape[index].toInt() + op.value).toUByte()
            }
            is Print -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                stdout.write(tape[index].toInt())
            }
            is Input -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = stdin.read().toUByte()
            }
            is Loop -> {
                while (tape[pointer].toInt() != 0) {
                    pointer = runImpl(tape, pointer, op, stdout, stdin)
                }
            }
            is SetToConstant -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = op.value
            }
            is Copy -> {
                val currentValue = tape[pointer].toInt()
                tape[pointer] = 0u
                for ((offset, multiplier) in op.multipliers) {
                    val index = pointer.wrappingAdd(offset, TAPE_SIZE)
                    tape[index] = (tape[index].toInt() + currentValue * multiplier).toUByte()
                }
            }
        }
        insn++
    }

    return pointer
}