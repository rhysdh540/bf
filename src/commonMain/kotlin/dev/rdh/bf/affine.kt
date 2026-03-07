package dev.rdh.bf

/**
 * Backend-oriented affine IR used by code generators.
 *
 * This IR has explicit block semantics:
 * - offsets are normalized relative to a block base shift
 * - writes are grouped into batches with entry-state references
 * - [BFAffinePrint]/[BFAffineInput] act as ordering barriers between write batches
 */
sealed interface BFAffineOp

/**
 * A linearized region of operations with no loop boundaries.
 *
 * Semantics:
 * 1. Shift pointer by [baseShift]
 * 2. Execute [segments] in order
 * 3. Shift pointer by ([pointerDelta] - [baseShift])
 */
data class BFAffineBlock(
    val baseShift: Int,
    val pointerDelta: Int,
    val segments: List<BFAffineSegment>,
) : BFAffineOp

/**
 * Loop node in affine IR.
 *
 * Semantics are equivalent to `while (cell[pointer] != 0) { body }`.
 */
data class BFAffineLoop(private val body: List<BFAffineOp>) : BFAffineOp, List<BFAffineOp> by body

/**
 * Sub-operations inside a [BFAffineBlock].
 *
 * Write batches and I/O barriers are intentionally split so backends can
 * preserve "simultaneous batch write" semantics without extra analysis.
 */
sealed interface BFAffineSegment

/**
 * A simultaneous writeback batch.
 *
 * Each write expression reads from the block entry state (or from the prior
 * barrier snapshot), not from writes that appear earlier in this same batch.
 */
data class BFAffineWriteBatch(val writes: List<BFAffineWrite>) : BFAffineSegment

/** Print the byte at [offset] relative to the block-shifted pointer. */
data class BFAffinePrint(val offset: Int) : BFAffineSegment

/** Read one byte into [offset] relative to the block-shifted pointer. */
data class BFAffineInput(val offset: Int) : BFAffineSegment

/** Store [expr] into `cell[pointer + offset]`. */
data class BFAffineWrite(
    val offset: Int,
    val expr: BFAffineExpr,
)

/**
 * Affine expression over referenced cell values.
 *
 * `constant + sum(terms[i].coefficient * ref(terms[i].offset))`
 */
data class BFAffineExpr(
    val constant: Int = 0,
    val terms: List<BFAffineTerm> = emptyList(),
)

data class BFAffineTerm(
    val offset: Int,
    val coefficient: Int,
)

private data class MutableExpr(
    val terms: MutableMap<Int, Int> = mutableMapOf(),
    var constant: Int = 0,
) {
    fun copyExpr(): MutableExpr = MutableExpr(
        terms = terms.toMutableMap(),
        constant = constant,
    )
}

private sealed interface LoweringStep
private data class StepValueChange(val offset: Int, val value: Int) : LoweringStep
private data class StepPrint(val offset: Int) : LoweringStep
private data class StepInput(val offset: Int) : LoweringStep
private data class StepSetToConstant(val offset: Int, val value: UByte) : LoweringStep
private data class StepCopy(val sourceOffset: Int, val targetOffset: Int, val multiplier: Int) : LoweringStep

private fun refExpr(offset: Int): MutableExpr = MutableExpr(terms = mutableMapOf(offset to 1))
private fun constExpr(value: Int): MutableExpr = MutableExpr(constant = value)

private operator fun MutableExpr.plus(other: MutableExpr): MutableExpr {
    val out = copyExpr()
    out.constant += other.constant
    for ((ref, coeff) in other.terms) {
        val next = (out.terms[ref] ?: 0) + coeff
        if (next == 0) {
            out.terms.remove(ref)
        } else {
            out.terms[ref] = next
        }
    }
    return out
}

private operator fun MutableExpr.times(multiplier: Int): MutableExpr {
    if (multiplier == 0) return constExpr(0)
    val out = copyExpr()
    out.constant *= multiplier
    val refs = out.terms.keys.toList()
    for (ref in refs) {
        val next = (out.terms[ref] ?: 0) * multiplier
        if (next == 0) {
            out.terms.remove(ref)
        } else {
            out.terms[ref] = next
        }
    }
    return out
}

/**
 * Lowers a loop-free basic block to affine form.
 *
 * Offsets are normalized using the returned [BFAffineBlock.baseShift], so
 * segment offsets can be addressed from a single temporary pointer base.
 */
private fun lowerLinearBlock(block: List<BFOperation>): BFAffineBlock? {
    if (block.isEmpty()) return null

    var pointerDelta = 0
    val steps = mutableListOf<LoweringStep>()

    for (op in block) {
        when (op) {
            is PointerMove -> pointerDelta += op.value
            is ValueChange -> steps += StepValueChange(offset = pointerDelta + op.offset, value = op.value)
            is Print -> steps += StepPrint(offset = pointerDelta + op.offset)
            is Input -> steps += StepInput(offset = pointerDelta + op.offset)
            is SetToConstant -> steps += StepSetToConstant(offset = pointerDelta + op.offset, value = op.value)
            is Copy -> steps += StepCopy(
                sourceOffset = pointerDelta,
                targetOffset = pointerDelta + op.offset,
                multiplier = op.multiplier,
            )
            is Loop -> error("Loop should not appear in linear block lowering")
        }
    }

    if (steps.isEmpty()) {
        return if (pointerDelta == 0) {
            null
        } else {
            BFAffineBlock(
                baseShift = 0,
                pointerDelta = pointerDelta,
                segments = emptyList(),
            )
        }
    }

    var baseShift = minOf(0, pointerDelta)
    for (step in steps) {
        baseShift = when (step) {
            is StepValueChange -> minOf(baseShift, step.offset)
            is StepPrint -> minOf(baseShift, step.offset)
            is StepInput -> minOf(baseShift, step.offset)
            is StepSetToConstant -> minOf(baseShift, step.offset)
            is StepCopy -> minOf(baseShift, step.sourceOffset, step.targetOffset)
        }
    }

    fun eff(offset: Int): Int = offset - baseShift
    val segments = mutableListOf<BFAffineSegment>()
    val state = mutableMapOf<Int, MutableExpr>()

    fun resolve(offset: Int): MutableExpr = state[offset]?.copyExpr() ?: refExpr(offset)

    fun flushState() {
        if (state.isEmpty()) return

        val writes = state.keys.sorted().map { target ->
            val expr = state[target]!!
            val terms = expr.terms.entries
                .filter { it.value != 0 }
                .sortedBy { it.key }
                .map { (offset, coefficient) ->
                    BFAffineTerm(offset = offset, coefficient = coefficient)
                }
            BFAffineWrite(
                offset = target,
                expr = BFAffineExpr(
                    constant = expr.constant,
                    terms = terms,
                )
            )
        }
        segments += BFAffineWriteBatch(writes)
        state.clear()
    }

    for (step in steps) {
        when (step) {
            is StepValueChange -> {
                val offset = eff(step.offset)
                state[offset] = resolve(offset) + constExpr(step.value)
            }
            is StepSetToConstant -> {
                state[eff(step.offset)] = constExpr(step.value.toInt())
            }
            is StepCopy -> {
                val sourceOffset = eff(step.sourceOffset)
                val targetOffset = eff(step.targetOffset)
                val source = resolve(sourceOffset)
                val target = resolve(targetOffset)
                state[targetOffset] = target + (source * step.multiplier)
            }
            is StepPrint -> {
                flushState()
                segments += BFAffinePrint(offset = eff(step.offset))
            }
            is StepInput -> {
                flushState()
                segments += BFAffineInput(offset = eff(step.offset))
            }
        }
    }

    flushState()

    val normalizedBaseShift = if (segments.isEmpty()) 0 else baseShift
    return BFAffineBlock(
        baseShift = normalizedBaseShift,
        pointerDelta = pointerDelta,
        segments = segments,
    )
}

/**
 * Lowers operation IR to affine IR.
 *
 * This is the shared lowering entry point backends should use before codegen.
 */
fun bfLowerAffine(program: List<BFOperation>): List<BFAffineOp> {
    val lowered = mutableListOf<BFAffineOp>()

    var i = 0
    while (i < program.size) {
        val op = program[i]
        if (op is Loop) {
            lowered += BFAffineLoop(bfLowerAffine(op)) as BFAffineOp
            i++
            continue
        }

        val start = i
        while (i < program.size && program[i] !is Loop) {
            i++
        }
        val block = lowerLinearBlock(program.subList(start, i))
        if (block != null) {
            lowered += block
        }
    }

    return lowered
}
