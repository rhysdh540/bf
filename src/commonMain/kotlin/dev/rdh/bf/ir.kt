package dev.rdh.bf

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
data class AffineExpr(val constant: Int = 0, val terms: List<Term> = emptyList())

data class Term(val coeff: Int, val offsets: List<Int>)

