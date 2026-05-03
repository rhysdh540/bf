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

internal fun Expr.constantValue(): Long? = when (this) {
    is Const -> value.toLong()
    is Cell, is GetTemp -> null
    is Add -> {
        var sum = 0L
        for (term in terms) {
            sum += term.constantValue() ?: return null
        }
        sum
    }

    is Mul -> {
        var product = 1L
        for (factor in factors) {
            product *= factor.constantValue() ?: return null
        }
        product
    }

    is Neg -> -(value.constantValue() ?: return null)
    is ExactDiv -> exactDivide(numerator.constantValue() ?: return null, divisor.toLong())
    is Choose -> chooseConst(value.constantValue() ?: return null, degree)
}

internal fun truncateByte(value: Long): Int {
    val mod = value % 256L
    return if (mod >= 0) mod.toInt() else (mod + 256L).toInt()
}

internal fun Expr.truncateKnownByte(): Expr {
    val constant = constantValue() ?: return this
    return Const(truncateByte(constant))
}

internal fun shiftExpr(expr: Expr, delta: Int): Expr = when {
    delta == 0 -> expr
    else -> when (expr) {
        is Const -> expr
        is Cell -> Cell(expr.offset + delta)
        is GetTemp -> expr
        is Add -> expr.terms.fold(Const.ZERO as Expr) { acc, term -> acc + shiftExpr(term, delta) }
        is Mul -> expr.factors.fold(Const.ONE as Expr) { acc, factor -> acc * shiftExpr(factor, delta) }
        is Neg -> -shiftExpr(expr.value, delta)
        is ExactDiv -> shiftExpr(expr.numerator, delta) / expr.divisor
        is Choose -> choose(shiftExpr(expr.value, delta), expr.degree)
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
    is Cell -> if (this.offset == offset) 0 else null
    is Add -> {
        var baseCount = 0
        var constant = 0
        for (term in terms) {
            when (term) {
                Cell(offset) -> baseCount++
                is Const -> constant += term.value
                else -> return null
            }
        }
        if (baseCount == 1) constant else null
    }

    else -> null
}

internal fun subtractBase(expr: Expr, base: Expr): Expr = when {
    expr == base -> Const.ZERO
    base == Const.ZERO -> expr
    base is Cell && expr is Cell -> if (expr.offset == base.offset) Const.ZERO else expr + -base
    base is Cell && expr is Add -> {
        var removed = false
        var result: Expr = Const.ZERO
        for (term in expr.terms) {
            if (!removed && term == base) {
                removed = true
                continue
            }
            result += term
        }
        if (removed) result else expr + -base
    }

    else -> expr + -base
}

internal fun chooseConst(value: Long, degree: Int): Long {
    var result = 1L
    for (i in 0 until degree) {
        result = exactDivide(result * (value - i), i + 1L)
    }
    return result
}

internal fun exactDivide(numerator: Long, divisor: Long): Long {
    require(divisor != 0L) { "division by zero" }
    require(numerator % divisor == 0L) { "inexact division: $numerator / $divisor" }
    return numerator / divisor
}
