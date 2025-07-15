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
                    pointer = runImpl(tape, pointer, op.contents, stdout, stdin)
                }
            }
            is SetToConstant -> {
                val index = pointer.wrappingAdd(op.offset, TAPE_SIZE)
                tape[index] = op.value
            }
        }
        insn++
    }

    return pointer
}