package dev.rdh.bf

internal fun add(vararg terms: Expr): Expr {
    val flat = mutableListOf<Expr>()
    var constant = 0

    for (term in terms) {
        when (term) {
            is Const -> constant += term.value
            is Add -> flat += term.terms
            else -> flat += term
        }
    }

    val filtered = flat.filterNot { it == Const(0) }.toMutableList()
    if (constant != 0) filtered += Const(constant)

    return when (filtered.size) {
        0 -> Const(0)
        1 -> filtered.single()
        else -> Add(filtered)
    }
}

internal fun mul(vararg factors: Expr): Expr {
    val flat = mutableListOf<Expr>()
    var constant = 1

    for (factor in factors) {
        when (factor) {
            is Const -> {
                if (factor.value == 0) return Const(0)
                constant *= factor.value
            }

            is Mul -> flat += factor.factors
            else -> flat += factor
        }
    }

    val filtered = flat.filterNot { it == Const(1) }.toMutableList()
    if (constant != 1 || filtered.isEmpty()) filtered.add(0, Const(constant))

    return when (filtered.size) {
        0 -> Const(1)
        1 -> filtered.single()
        else -> Mul(filtered)
    }
}

internal fun neg(value: Expr): Expr = when (value) {
    is Const -> Const(-value.value)
    is Neg -> value.value
    else -> Neg(value)
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
    is Add -> add(*expr.terms.map { substitute(it, ptrOffset, readCell, readTemp) ?: return null }.toTypedArray())
    is Mul -> mul(*expr.factors.map { substitute(it, ptrOffset, readCell, readTemp) ?: return null }.toTypedArray())
    is Neg -> neg(substitute(expr.value, ptrOffset, readCell, readTemp) ?: return null)
    is ExactDiv -> {
        val numerator = substitute(expr.numerator, ptrOffset, readCell, readTemp) ?: return null
        ExactDiv(numerator, expr.divisor)
    }

    is Choose -> {
        val value = substitute(expr.value, ptrOffset, readCell, readTemp) ?: return null
        Choose(value, expr.degree)
    }
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
