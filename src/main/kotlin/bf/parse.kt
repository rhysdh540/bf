package bf

/**
 * Parses a Brainfuck program from a string into a list of [BFOperation]s.
 * @param program The Brainfuck program as a `CharSequence`
 * @return A list of [BFOperation]s representing the parsed program.
 */
fun bfParse(program: CharSequence): List<BFOperation> {
    return bfParse(program.toList())
}

/**
 * Parses a Brainfuck program from a list of characters into a list of [BFOperation]s.
 * @param program The Brainfuck program as an `Iterable<Char>`.
 * @return A list of [BFOperation]s representing the parsed program.
 */
fun bfParse(program: Iterable<Char>): List<BFOperation> {
    return bfParse(program.toList().toCharArray())
}

/**
 * Parses a Brainfuck program from a character array into a list of [BFOperation]s.
 * @param program The Brainfuck program as a `CharArray`.
 * @return A list of [BFOperation]s representing the parsed program.
 */
fun bfParse(program: CharArray): List<BFOperation> {
    val loops = mutableListOf<MutableList<BFOperation>>()
    val result = mutableListOf<BFOperation>()
    fun current(): MutableList<BFOperation> {
        return if (loops.isNotEmpty()) loops.last() else result
    }

    var i = 0
    var lastLoopStart = -1
    for (c in program) {
        when (c) {
            '>' -> current().add(PointerMove.Right)
            '<' -> current().add(PointerMove.Left)
            '+' -> current().add(ValueChange.Plus)
            '-' -> current().add(ValueChange.Minus)
            '.' -> current().add(Print)
            ',' -> current().add(Input)
            '[' -> {
                loops.add(mutableListOf())
                lastLoopStart = i
            }
            ']' -> {
                if (loops.isEmpty()) {
                    throw IllegalArgumentException("Unmatched ']' at index $i")
                }
                val loop = loops.removeLast()
                current().add(Loop(loop))
            }
        }
        i++
    }

    if (loops.isNotEmpty()) {
        throw IllegalArgumentException("Unmatched '[' at index $lastLoopStart")
    }

    return result
}