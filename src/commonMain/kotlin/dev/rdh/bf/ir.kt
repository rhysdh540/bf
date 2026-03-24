package dev.rdh.bf

import dev.rdh.bf.util.defaultMapOf

sealed interface BfBlockOp

/**
 * move pointer by [workingOffset], run all of [ops], move by `pointerDelta - workingOffset`
 */
data class Block(
    val pointerDelta: Int,
    val ops: List<BfOperation>,
    val workingOffset: Int
) : BfBlockOp

/**
 * run body while `tape[ptr] != 0`
 */
data class Loop(val body: List<BfBlockOp>) : BfBlockOp

sealed interface BfOperation

// write out/read in to tape[ptr + offset]
data class Output(val offset: Int) : BfOperation
data class Input(val offset: Int) : BfOperation

// perform all these writes in a row
data class WriteBatch(val writes: List<Write>) : BfOperation {
    val readOffsets by lazy {
        writes.flatMap { it.expr.terms.flatMap { it.offsets } }
    }
}

/**
 * put the result of [expr] (terms evaluated from before any other writes in this batch happen)
 * into `tape[ptr + offset]`
 */
data class Write(val offset: Int, val expr: AffineExpr)

/**
 * evaluates to `constant + sum(terms[i].coeff * prod( tape[ptr + terms[i].offsets[j]] ))`
 */
data class AffineExpr(val constant: Int = 0, val terms: List<Term> = emptyList()) {
    companion object {
        val ZERO = AffineExpr()
        fun cell(offset: Int) = AffineExpr(terms = listOf(Term(1, setOf(offset))))
        fun const(value: Int) = AffineExpr(constant = value)
    }

    data class Term(val coeff: Int, val offsets: Set<Int>)

    operator fun plus(other: AffineExpr): AffineExpr {
        val termMap = defaultMapOf<Set<Int>, Int> { 0}
        for (t in this.terms + other.terms) {
            termMap[t.offsets] += t.coeff
        }

        return AffineExpr(
            constant + other.constant,
            termMap.filterValues { it != 0 }.map { (k, v) -> Term(v, k) }
        )
    }

    operator fun times(scalar: Int): AffineExpr {
        if (scalar == 0) return ZERO
        return AffineExpr(
            constant * scalar,
            terms.mapNotNull { t ->
                val c = t.coeff * scalar
                if (c != 0) Term(c, t.offsets) else null
            }
        )
    }

    val isConstant
        get() = terms.isEmpty()

    operator fun minus(other: AffineExpr) = this + other * -1
    operator fun unaryMinus() = this * -1

    operator fun times(other: AffineExpr): AffineExpr {
        var result = AffineExpr()

        // constant * other
        if (constant != 0) result += other * constant

        // terms * other
        for (t1 in terms) {
            // t1 * other.constant
            if (other.constant != 0) {
                result += AffineExpr(terms = listOf(Term(t1.coeff * other.constant, t1.offsets)))
            }
            // t1 * other.terms
            for (t2 in other.terms) {
                val c = t1.coeff * t2.coeff
                if (c != 0) {
                    result += AffineExpr(terms = listOf(Term(c, t1.offsets + t2.offsets)))
                }
            }
        }

        return result
    }

    /**
     * replace each `tape[offset]` reference with the mapped expression; unmapped offsets are left as-is
     */
    fun substitute(mapping: Map<Int, AffineExpr>): AffineExpr {
        var result = const(constant)
        for (term in terms) {
            var product: AffineExpr = const(term.coeff)
            for (offset in term.offsets) {
                product *= (mapping[offset] ?: cell(offset))
            }
            result += product
        }
        return result
    }
}
