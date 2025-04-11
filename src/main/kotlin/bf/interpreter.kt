package bf

@OptIn(ExperimentalUnsignedTypes::class)
fun bfRun(program: Iterable<BFOperation>,
          stdout: (Int) -> Unit = { print(it.toChar()) },
          stdin: () -> Int = { System.`in`.read() },
) {
    runImpl(UByteArray(TAPE_SIZE), 0, program.toList().toTypedArray(), stdout, stdin)
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun runImpl(tape: UByteArray, pointer: Int,
                    program: Array<BFOperation>,
                    stdout: (Int) -> Unit, stdin: () -> Int): Int {
    var pointer = pointer
    var insn = 0

    while (insn < program.size) {
        val op = program[insn]
        when (op) {
            is PointerMove -> pointer = pointer.wrappingAdd(op.value, TAPE_SIZE)
            is ValueChange -> tape[pointer] = tape[pointer].toInt().wrappingAdd(op.value, 256).toUByte()
            is Print -> stdout(tape[pointer].toInt())
            is Input -> tape[pointer] = stdin().toUByte()
            is Loop -> {
                var arr = op.toTypedArray()
                while (tape[pointer].toInt() != 0) {
                    pointer = runImpl(tape, pointer, arr, stdout, stdin)
                }
            }
            SetToZero -> tape[pointer] = 0u
        }
        insn++
    }

    return pointer
}