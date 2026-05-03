package dev.rdh.bf

import kotlin.jvm.JvmInline

@JvmInline
value class Temp(val id: Int)

/**
 * backend-neutral executable IR for bf programs
 *
 * - arithmetic in [Expr] happens in widened integer space
 * - [Store] and [Write] truncate back to the tape cell width
 * - snapshot semantics from the frontend are made explicit
 *   by lowering old cell reads into [SetTemp] before any dependent stores
 */
sealed interface Op

data class MovePtr(val delta: Int) : Op

data class SetTemp(val temp: Temp, val value: Expr) : Op

data class Store(val offset: Int, val value: Expr) : Op

data class Read(val offset: Int) : Op

data class Write(val value: Expr) : Op

data class Conditional(val offset: Int, val body: List<Op>) : Op // if non-zero

data class Loop(val offset: Int, val body: List<Op>) : Op // while non-zero

sealed interface Expr {
    operator fun plus(other: Expr): Expr {
        val terms = arrayOf(this, other)
        val flat = mutableListOf<Expr>()
        var constant = 0
        for (term in terms) {
            when (term) {
                is Const -> constant += term.value
                is Add -> flat += term.terms
                else -> flat += term
            }
        }
        val filtered = flat.filterNot { it == Const.ZERO }.toMutableList()
        if (constant != 0) filtered += Const(constant)
        return when (filtered.size) {
            0 -> Const.ZERO
            1 -> filtered.single()
            else -> Add(filtered)
        }
    }
    operator fun times(other: Expr): Expr {
        val flat = mutableListOf<Expr>()
        var constant = 1

        for (factor in arrayOf(this, other)) {
            when (factor) {
                is Const -> {
                    if (factor.value == 0) return Const.ZERO
                    constant *= factor.value
                }

                is Mul -> flat += factor.factors
                else -> flat += factor
            }
        }

        val filtered = flat.filterNot { it == Const.ONE }.toMutableList()
        if (constant != 1 || filtered.isEmpty()) filtered.add(0, Const(constant))

        return when (filtered.size) {
            0 -> Const.ONE
            1 -> filtered.single()
            else -> Mul(filtered)
        }
    }
    operator fun div(other: Int): Expr {
        if (other == 1) return this
        return ExactDiv(this, other)
    }
    operator fun unaryMinus(): Expr = Neg(this)
}

data class Const(val value: Int) : Expr {
    override fun unaryMinus(): Expr = Const(-value)
    override fun div(other: Int): Expr {
        if (other == 1) return this
        if (value % other == 0) return Const(value / other)
        return ExactDiv(this, other)
    }
    companion object {
        val ZERO = Const(0)
        val ONE = Const(1)
    }
}

data class Cell(val offset: Int) : Expr

data class GetTemp(val temp: Temp) : Expr

data class Add(val terms: List<Expr>) : Expr {
    constructor(vararg terms: Expr) : this(terms.toList())
    init {
        require(terms.isNotEmpty()) { "empty add" }
    }
}

data class Mul(val factors: List<Expr>) : Expr {
    constructor(vararg factors: Expr) : this(factors.toList())
    init {
        require(factors.isNotEmpty()) { "empty mul" }
    }
}

data class Neg(val value: Expr) : Expr {
    override fun unaryMinus(): Expr = value
}

/**
 * explicit modulo-256 truncation back into tape-cell space
 */
data class ByteTruncate(val value: Expr) : Expr

/**
 * **EXACT** division in the widened domain before the eventual byte truncation
 */
data class ExactDiv(val numerator: Expr, val divisor: Int) : Expr {
    init {
        require(divisor != 0) { "division by zero" }
    }
}

/**
 * integer-valued binomial term C(value, degree)
 */
data class Choose(val value: Expr, val degree: Int) : Expr {
    init {
        require(degree >= 0) { "negative choose degree: $degree" }
    }
}
