package bf

/**
 * Optimises a Brainfuck program by removing unnecessary operations and combining where possible.
 *
 * Optimisations include:
 * - Combining consecutive `+` and `-` operations into one operation.
 * - Combining consecutive `>` and `<` operations into one operation.
 * - Replacing the pattern `[-]` and `[+]` with a [SetToZero] operation.
 *
 * @param program The Brainfuck program to optimise.
 * @return The optimised Brainfuck program.
 */
fun bfOptimise(program: Iterable<BFOperation>): List<BFOperation> {
    val program = program.toMutableList()
    val optimisedProgram = mutableListOf<BFOperation>()

    var i = 0
    while (i < program.size) {
        val op = program[i]
        when (op) {
            is PointerMove -> {
                var num = op.value
                while (i + 1 < program.size && program[i + 1] is PointerMove) {
                    num += (program[i + 1] as PointerMove).value
                    i++
                }
                num = num % TAPE_SIZE
                if (num != 0) {
                    optimisedProgram.add(PointerMove(num))
                }
            }

            is ValueChange -> {
                var num = op.value
                while (i + 1 < program.size && program[i + 1] is ValueChange) {
                    num += (program[i + 1] as ValueChange).value
                    i++
                }
                num = num % 256
                if (num != 0) {
                    optimisedProgram.add(ValueChange(num))
                }
            }

            is Loop -> {
                if (op.singleOrNull() is ValueChange) {
                    optimisedProgram.add(SetToZero)
                } else {
                    optimisedProgram.add(Loop(bfOptimise(op)))
                }
            }

            else -> optimisedProgram.add(op)
        }
        i++
    }

    return optimisedProgram
}

/**
 * Removes unnecessary operations from a Brainfuck program.
 * This will:
 * - remove any [PointerMove] or [ValueChange] operations that have `value == 0`
 * - remove any loops at the beginning of the program, which will never run
 * - remove any [PointerMove], [ValueChange] or [SetToZero] operations at the end of the program
 */
fun bfStrip(program: Iterable<BFOperation>): List<BFOperation> {
    val loop = program is Loop
    var program = program.toMutableList()

    if (!loop) {
        while (program.first() is Loop || program.first() is SetToZero) {
            program.removeFirst()
        }
    }

    program.removeAll {
        (it == PointerMove && (it as PointerMove).value == 0) ||
                (it == ValueChange && (it as ValueChange).value == 0)
    }

    if (!loop) {
        while (program.isNotEmpty() && program.last().let {
            it is PointerMove || it is ValueChange || it is SetToZero
        }) {
            program.removeAt(program.lastIndex)
        }
    }

    return program.map { if (it is Loop) Loop(bfStrip(it)) else it }
}