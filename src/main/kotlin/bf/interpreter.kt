package bf

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer

@OptIn(ExperimentalUnsignedTypes::class)
fun bfRun(program: Iterable<BFOperation>,
          stdout: Writer = SysOutWriter,
          stdin: Reader = System.`in`.reader(),
) {
    runImpl(UByteArray(TAPE_SIZE), 0, program.toList().toTypedArray(), stdout, stdin)
    stdout.flush()
}

fun bfRun(program: Iterable<BFOperation>, stdout: OutputStream, stdin: InputStream) {
    bfRun(program, stdout.writer(), stdin.reader())
}

@OptIn(ExperimentalUnsignedTypes::class)
private fun runImpl(tape: UByteArray, pointer: Int,
                    program: Array<BFOperation>,
                    stdout: Writer, stdin: Reader): Int {
    var pointer = pointer
    var insn = 0

    while (insn < program.size) {
        val op = program[insn]
        when (op) {
            is PointerMove -> pointer = pointer.wrappingAdd(op.value, TAPE_SIZE)
            is ValueChange -> tape[pointer] = tape[pointer].toInt().wrappingAdd(op.value, 256).toUByte()
            is Print -> stdout.write(tape[pointer].toInt())
            is Input -> tape[pointer] = stdin.read().toUByte()
            is Loop -> {
                while (tape[pointer].toInt() != 0) {
                    pointer = runImpl(tape, pointer, op.contents, stdout, stdin)
                }
            }
            SetToZero -> tape[pointer] = 0u
        }
        insn++
    }

    return pointer
}