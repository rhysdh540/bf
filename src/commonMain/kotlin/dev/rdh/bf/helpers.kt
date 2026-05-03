package dev.rdh.bf

internal fun choose(value: Expr, degree: Int): Expr {
    require(degree >= 0) { "negative choose degree: $degree" }

    return when {
        degree == 0 -> Const.ONE
        degree == 1 -> value
        value is Const -> Const(chooseConst(value.value.toLong(), degree).toInt())
        else -> Choose(value, degree)
    }
}

internal fun Expr.readOffsets(): List<Int> = when (this) {
    is Const -> emptyList()
    is Cell -> listOf(offset)
    is GetTemp -> emptyList()
    is Add -> terms.flatMap { it.readOffsets() }
    is Mul -> factors.flatMap { it.readOffsets() }
    is Neg -> value.readOffsets()
    is ExactDiv -> numerator.readOffsets()
    is Choose -> value.readOffsets()
}

internal fun substitute(
    expr: Expr,
    ptrOffset: Int,
    readCell: (Int) -> Expr,
    readTemp: (Temp) -> Expr?,
): Expr? = when (expr) {
    is Const -> expr
    is Cell -> readCell(ptrOffset + expr.offset)
    is GetTemp -> readTemp(expr.temp)
    is Add -> {
        var sum: Expr = Const.ZERO
        for (term in expr.terms) {
            sum += substitute(term, ptrOffset, readCell, readTemp) ?: return null
        }
        sum
    }

    is Mul -> {
        var product: Expr = Const.ONE
        for (factor in expr.factors) {
            product *= substitute(factor, ptrOffset, readCell, readTemp) ?: return null
        }
        product
    }

    is Neg -> -(substitute(expr.value, ptrOffset, readCell, readTemp) ?: return null)
    is ExactDiv -> (substitute(expr.numerator, ptrOffset, readCell, readTemp) ?: return null) / expr.divisor
    is Choose -> choose(substitute(expr.value, ptrOffset, readCell, readTemp) ?: return null, expr.degree)
}

internal fun <T> orderWrites(
    writes: List<T>,
    offsetOf: (T) -> Int,
    valueOf: (T) -> Expr,
): List<T> {
    if (writes.size <= 1) return writes

    val targetSet = writes.mapTo(mutableSetOf(), offsetOf)
    val writeByOffset = writes.associateBy(offsetOf)
    val outgoing = targetSet.associateWith { mutableSetOf<Int>() }
    val indegree = targetSet.associateWith { 0 }.toMutableMap()

    for (write in writes) {
        val offset = offsetOf(write)
        val reads = valueOf(write).readOffsets()
            .filter { it in targetSet && it != offset }
            .toSet()
        for (read in reads) {
            if (outgoing.getValue(offset).add(read)) {
                indegree[read] = indegree.getValue(read) + 1
            }
        }
    }

    val ready = targetSet.filter { indegree.getValue(it) == 0 }.sorted().toMutableList()
    val ordered = mutableListOf<Int>()

    while (ready.isNotEmpty()) {
        val target = ready.removeFirst()
        ordered += target
        for (next in outgoing.getValue(target)) {
            val nextIndegree = indegree.getValue(next) - 1
            indegree[next] = nextIndegree
            if (nextIndegree == 0) {
                val idx = ready.binarySearch(next).let { if (it < 0) -(it + 1) else it }
                ready.add(idx, next)
            }
        }
    }

    if (ordered.size < writes.size) {
        val emitted = ordered.toSet()
        ordered += writes.map(offsetOf).filter { it !in emitted }
    }

    return ordered.map { writeByOffset.getValue(it) }
}

internal fun Expr.additiveDelta(offset: Int): Int? = when (this) {
    is Add -> {
        if (terms.size != 2) return null
        val cell = terms.firstOrNull { it == Cell(offset) }
        val constant = terms.firstOrNull { it is Const } as? Const
        if (cell == null || constant == null) null else constant.value
    }

    else -> null
}

private fun chooseConst(value: Long, degree: Int): Long {
    var result = 1L
    for (i in 0 until degree) {
        result = exactDivide(result * (value - i), i + 1L)
    }
    return result
}

private fun exactDivide(numerator: Long, divisor: Long): Long {
    require(divisor != 0L) { "division by zero" }
    require(numerator % divisor == 0L) { "inexact division: $numerator / $divisor" }
    return numerator / divisor
}
