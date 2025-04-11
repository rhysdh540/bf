package bf

import kotlin.math.absoluteValue

fun bfStringify(program: Iterable<BFOperation>): String {
    return program.joinToString("") { op ->
        when (op) {
            is PointerMove -> (if (op.value > 0) ">" else "<").repeat(op.value.absoluteValue)
            is ValueChange -> (if (op.value > 0) "+" else "-").repeat(op.value.absoluteValue)
            Print -> "."
            Input -> ","
            is Loop -> "[${bfStringify(op)}]"
            SetToZero -> "[-]"
        }
    }
}

fun Int.wrappingAdd(value: Int, limit: Int): Int {
    var a = this + value
    while (a >= limit) {
        a -= limit
    }
    while (a < 0) {
        a += limit
    }

    return a
}