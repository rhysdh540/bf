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
    val result = (this + value) % limit
    return if (result < 0) result + limit else result
}