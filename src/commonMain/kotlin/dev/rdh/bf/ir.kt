package dev.rdh.bf

import dev.rdh.bf.util.defaultMapOf

sealed interface BfBlockOp

/**
 * move pointer by [workingOffset], perform all [writes], move by `pointerDelta - workingOffset`
 */
data class WriteBlock(
    val pointerDelta: Int,
    val writes: List<Write>,
    val workingOffset: Int,
) : BfBlockOp {
    val readOffsets by lazy {
        writes.flatMap { it.expr.terms.flatMap { it.offsets } }.distinct().sorted()
    }

    /**
     * does this block read and write to the same cell, directly or indirectly?
     * (is there a cycle in the write dependency graph?)
     */
    val hasCycles: Boolean by lazy {
        val writeTargets = writes.map { it.offset }.toSet()
        val visited = mutableSetOf<Int>()
        val inStack = mutableSetOf<Int>()
        val adj = writes.associate { w ->
            w.offset to w.expr.terms
                .flatMap { it.offsets }
                .filter { it in writeTargets && it != w.offset }
                .toSet()
        }

        fun dfs(node: Int): Boolean {
            if (node in inStack) return true
            if (node in visited) return false
            visited += node
            inStack += node
            for (next in adj[node] ?: emptySet()) {
                if (dfs(next)) return true
            }
            inStack -= node
            return false
        }

        writeTargets.any { dfs(it) }
    }

    val snapshotIndex: Map<Int, Int> by lazy {
        readOffsets.withIndex().associate { (i, off) -> off to i }
    }
}

/**
 * perform I/O operations, then move pointer by [pointerDelta]
 */
data class IOBlock(val pointerDelta: Int, val ops: List<BfIOOp>) : BfBlockOp

/**
 * run body while `tape[ptr] != 0`
 */
data class Loop(val body: List<BfBlockOp>) : BfBlockOp

/**
 * run body once if `tape[ptr] != 0`
 *
 * structurally identical to a loop that executes at most once. this arises from
 * split-solving: the first block peels one iteration, the second block solves
 * the remainder in closed form, and the guard cell is zeroed — so the condition
 * can never be true a second time.
 *
 * unlike [Loop], a [Conditional] can be inlined by [mergeBlocks][Parser] when
 * the guard cell's symbolic value is a known nonzero constant, enabling further
 * optimization of enclosing loops.
 */
data class Conditional(val body: List<BfBlockOp>) : BfBlockOp

sealed interface BfIOOp

data class Output(val expr: Expression) : BfIOOp
data class Input(val offset: Int) : BfIOOp

/**
 * put the result of [expr] (terms evaluated from before any other writes in this batch happen)
 * into `tape[ptr + offset]`
 */
data class Write(val offset: Int, val expr: Expression)

/**
 * evaluates to `constant + sum(terms[i].coeff * prod( tape[ptr + terms[i].offsets[j]] ))`
 */
data class Expression(val constant: Int = 0, val terms: List<Term> = emptyList()) {
    companion object {
        val ZERO = Expression()
        fun cell(offset: Int) = Expression(terms = listOf(Term(1, listOf(offset))))
        fun const(value: Int) = Expression(constant = value)
    }

    data class Term(val coeff: Int, val offsets: List<Int>)

    operator fun plus(other: Expression): Expression {
        val termMap = defaultMapOf<List<Int>, Int> { 0 }
        for (t in this.terms + other.terms) {
            termMap[t.offsets] += t.coeff
        }

        return Expression(
            constant + other.constant,
            termMap.filterValues { it != 0 }.map { (k, v) -> Term(v, k) }
        )
    }

    operator fun times(scalar: Int): Expression {
        if (scalar == 0) return ZERO
        return Expression(
            constant * scalar,
            terms.mapNotNull { t ->
                val c = t.coeff * scalar
                if (c != 0) Term(c, t.offsets) else null
            }
        )
    }

    val isConstant
        get() = terms.isEmpty()

    operator fun minus(other: Expression) = this + other * -1
    operator fun unaryMinus() = this * -1

    operator fun times(other: Expression): Expression {
        var result = Expression()

        // constant * other
        if (constant != 0) result += other * constant

        // terms * other
        for (t1 in terms) {
            // t1 * other.constant
            if (other.constant != 0) {
                result += Expression(terms = listOf(Term(t1.coeff * other.constant, t1.offsets)))
            }
            // t1 * other.terms
            for (t2 in other.terms) {
                val c = t1.coeff * t2.coeff
                if (c != 0) {
                    result += Expression(terms = listOf(Term(c, (t1.offsets + t2.offsets).sorted())))
                }
            }
        }

        return result
    }

    /**
     * replace each `tape[offset]` reference with the mapped expression; unmapped offsets are left as-is
     */
    fun substitute(mapping: Map<Int, Expression>) = terms.fold(const(constant)) { acc, term ->
        acc + term.offsets.fold(const(term.coeff)) { prod, offset ->
            prod * (mapping[offset] ?: cell(offset))
        }
    }
}
