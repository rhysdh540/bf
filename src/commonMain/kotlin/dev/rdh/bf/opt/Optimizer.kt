package dev.rdh.bf.opt

import dev.rdh.bf.*

object Optimizer {
    data class LoopSummary(
        val guardOffset: Int,
        val tripCount: Expr,
        val pointerDelta: Int,
        val prologue: List<LoopWrite> = emptyList(),
        val writes: List<LoopWrite>,
    )

    data class LoopWrite(val offset: Int, val value: Expr)

    fun analyzeLoop(body: List<Op>): LoopSummary? = analyzeLoopAnalysis(body.map(Op::toAnalysisOp))

    fun analyzeLoop(body: List<Op>, readCell: (Int) -> Expr): LoopSummary? =
        analyzeLoopAnalysis(body.map(Op::toAnalysisOp), readCell)

    internal fun analyzeLoopAnalysis(body: List<AnalysisOp>): LoopSummary? = analyzeLoopAnalysis(body) { Cell(it) }

    internal fun analyzeLoopAnalysis(body: List<AnalysisOp>, readCell: (Int) -> Expr): LoopSummary? {
        val modifiedOffsets = controlEffect(body).modifiedOffsets
        val plainCandidate = extractCandidate(body) { Cell(it) }
        val entryBackedOffsets = plainCandidate?.writes
            ?.filter { (offset, expr) ->
                offset != 0 && offset !in expr.readOffsets()
            }
            ?.keys
            ?: emptySet()

        val entryBase: (Int) -> Expr = { offset ->
            when {
                offset == 0 -> Cell(0)
                offset !in modifiedOffsets -> readCell(offset)
                readCell(offset) == Const.ZERO -> Const.ZERO
                offset in entryBackedOffsets -> readCell(offset)
                else -> Cell(offset)
            }
        }
        val candidate = extractCandidate(body, entryBase) ?: plainCandidate ?: return null
        return summarizeWithPeeling(candidate, entryBase)
    }

    private data class Candidate(
        val guardOffset: Int,
        val pointerDelta: Int,
        val writes: Map<Int, Expr>,
    )

    private fun summarizeWithPeeling(
        candidate: Candidate,
        entryBase: (Int) -> Expr,
        peeledPrologue: List<LoopWrite> = emptyList(),
    ): LoopSummary? {
        if (candidate.pointerDelta != 0) return null

        summarizeCandidate(candidate, entryBase)?.let { summary ->
            return if (peeledPrologue.isEmpty()) {
                summary
            } else {
                summary.copy(prologue = peeledPrologue + summary.prologue)
            }
        }

        val peeled = peelCandidate(candidate, entryBase) ?: return null
        val prologue = peeledPrologue + orderWrites(
            candidate.writes.map { (offset, expr) -> LoopWrite(offset, expr) },
            LoopWrite::offset,
            LoopWrite::value,
        )
        return summarizeWithPeeling(peeled, entryBase, prologue)
    }

    private fun summarizeCandidate(
        candidate: Candidate,
        entryBase: (Int) -> Expr,
    ): LoopSummary? {
        val inductionExpr = candidate.writes[candidate.guardOffset] ?: return null
        val inductionDelta = inductionExpr.additiveDelta(candidate.guardOffset) ?: return null
        if (inductionDelta == 0) return null

        val inv = modInverse(-inductionDelta, 1 shl Byte.SIZE_BITS) ?: return null
        val tripCount = Const(inv) * Cell(candidate.guardOffset)
        val deltas = candidate.writes
            .filterKeys { it != candidate.guardOffset }
            .mapValues { (offset, expr) -> subtractBase(expr, entryBase(offset)) }

        val writes = solveRecurrences(
            guardOffset = candidate.guardOffset,
            inductionDelta = inductionDelta,
            deltas = deltas,
            entryBase = entryBase,
            tripCount = tripCount,
        ) ?: return null

        return LoopSummary(
            guardOffset = candidate.guardOffset,
            tripCount = tripCount,
            pointerDelta = candidate.pointerDelta,
            writes = writes,
        )
    }

    /**
     * Value as a function of the current iteration index k in binomial basis:
     * `sum coeffs[d] * C(k, d)`
     *
     * Degree-1 polynomials correspond to classic {start,+,step} add-recurrences.
     */
    private data class Recurrence(private val coeffs: List<Expr>) {
        val degree: Int
            get() = coeffs.lastIndex

        val isConstant: Boolean
            get() = coeffs.drop(1).all { it == Const.ZERO }

        val constantTerm: Expr
            get() = coeffs.firstOrNull() ?: Const.ZERO

        operator fun plus(other: Recurrence): Recurrence {
            val degree = maxOf(coeffs.size, other.coeffs.size)
            return Recurrence(
                List(degree) { index ->
                    coeffs.getOrElse(index) { Const.ZERO } + other.coeffs.getOrElse(index) { Const.ZERO }
                }
            ).trimmed()
        }

        operator fun times(other: Recurrence): Recurrence {
            val terms = MutableList(coeffs.size + other.coeffs.size - 1) { Const.ZERO as Expr }
            for ((leftDegree, leftCoeff) in coeffs.withIndex()) {
                if (leftCoeff == Const.ZERO) continue
                for ((rightDegree, rightCoeff) in other.coeffs.withIndex()) {
                    if (rightCoeff == Const.ZERO) continue
                    for (overlap in 0..minOf(leftDegree, rightDegree)) {
                        val outDegree = leftDegree + rightDegree - overlap
                        val factor = binomialProductFactor(leftDegree, rightDegree, overlap)
                        terms[outDegree] = terms[outDegree] + leftCoeff * rightCoeff * Const(factor)
                    }
                }
            }
            return Recurrence(terms).trimmed()
        }

        operator fun unaryMinus(): Recurrence = Recurrence(coeffs.map { -it }).trimmed()

        fun scaledBy(factor: Expr): Recurrence = when (factor) {
            Const.ZERO -> constant(Const.ZERO)
            Const.ONE -> this
            else -> Recurrence(coeffs.map { coeff -> coeff * factor }).trimmed()
        }

        fun integrate(start: Expr): Recurrence {
            val integrated = MutableList(coeffs.size + 1) { Const.ZERO as Expr }
            integrated[0] = start
            for ((degree, coeff) in coeffs.withIndex()) {
                integrated[degree + 1] = integrated[degree + 1] + coeff
            }
            return Recurrence(integrated).trimmed()
        }

        fun at(iterations: Expr): Expr {
            var value: Expr = Const.ZERO
            for ((degree, coeff) in coeffs.withIndex()) {
                if (coeff == Const.ZERO) continue
                value += when (degree) {
                    0 -> coeff
                    1 -> coeff * iterations
                    else -> coeff * choose(iterations, degree)
                }
            }
            return value
        }

        fun trimmed(): Recurrence {
            val trimmed = coeffs.toMutableList()
            while (trimmed.lastOrNull() == Const.ZERO) {
                trimmed.removeLast()
            }
            return Recurrence(trimmed)
        }

        companion object {
            fun constant(expr: Expr): Recurrence = Recurrence(listOf(expr))

            fun affine(start: Expr, step: Expr): Recurrence = Recurrence(listOf(start, step)).trimmed()
        }
    }

    private fun peelCandidate(candidate: Candidate, entryBase: (Int) -> Expr): Candidate? {
        val invariants = discoverInvariants(candidate.guardOffset, candidate.writes)
        if (invariants.isEmpty()) return null

        val peeledWrites = candidate.writes
            .asSequence()
            .filter { (offset, _) -> offset !in invariants.keys }
            .map { (offset, expr) -> offset to substituteOffsets(expr, invariants) }
            .filter { (offset, expr) -> expr != entryBase(offset) }
            .toMap()

        if (peeledWrites == candidate.writes) return null

        return Candidate(
            guardOffset = candidate.guardOffset,
            pointerDelta = candidate.pointerDelta,
            writes = peeledWrites,
        )
    }

    private fun discoverInvariants(
        guardOffset: Int,
        writes: Map<Int, Expr>,
    ): Map<Int, Expr> {
        val invariants = mutableMapOf<Int, Expr>()
        var changed = true

        while (changed) {
            changed = false
            val unresolved = writes.keys.filterTo(mutableSetOf()) { it == guardOffset || it !in invariants }

            for ((offset, expr) in writes) {
                if (offset == guardOffset || offset in invariants) continue

                val simplified = substituteOffsets(expr, invariants)
                if (simplified == Cell(offset)) {
                    invariants[offset] = simplified
                    changed = true
                    continue
                }

                if (simplified.readOffsets().none { it in unresolved }) {
                    invariants[offset] = simplified
                    changed = true
                }
            }
        }

        return invariants
    }

    private fun solveRecurrences(
        guardOffset: Int,
        inductionDelta: Int,
        deltas: Map<Int, Expr>,
        entryBase: (Int) -> Expr,
        tripCount: Expr,
    ): List<LoopWrite>? {
        val modifiedOffsets = deltas.keys + guardOffset
        val solved = mutableMapOf<Int, Recurrence>()
        solved[guardOffset] = Recurrence.affine(Cell(guardOffset), Const(inductionDelta))

        for ((offset, delta) in deltas) {
            if (delta == Const.ZERO) {
                solved[offset] = Recurrence.constant(entryBase(offset))
            }
        }

        val unresolved = deltas.keys
            .filter { it !in solved }
            .sorted()
            .toMutableList()
        var changed = true

        while (changed && unresolved.isNotEmpty()) {
            changed = false
            for (offset in unresolved.toList()) {
                val delta = deltas.getValue(offset)
                val deltaRecurrence = toRecurrence(delta, solved, modifiedOffsets) ?: continue
                solved[offset] = if (deltaRecurrence.isConstant) {
                    Recurrence.affine(entryBase(offset), deltaRecurrence.constantTerm)
                } else {
                    deltaRecurrence.integrate(entryBase(offset))
                }
                unresolved.remove(offset)
                changed = true
            }
        }

        if (unresolved.isNotEmpty()) return null

        val writes = mutableListOf(LoopWrite(guardOffset, Const.ZERO))
        for (offset in deltas.keys) {
            val exit = solved.getValue(offset).at(tripCount)
            if (exit != entryBase(offset)) {
                writes += LoopWrite(offset, exit)
            }
        }
        return orderWrites(writes, LoopWrite::offset, LoopWrite::value)
    }

    private fun extractCandidate(body: List<AnalysisOp>, entryCell: (Int) -> Expr): Candidate? {
        val state = SymbolicState(entryCell, exactCells = false)

        fun execute(ops: List<AnalysisOp>): Boolean {
            for (op in ops) {
                when (op) {
                    is AnalysisStep -> when (val step = op.op) {
                        is MovePtr, is SetTemp, is Store -> if (!state.apply(step)) return false
                        is Read, is Write -> return false
                        else -> error("Unexpected control op in AnalysisStep: $step")
                    }

                    is AnalysisSummary -> {
                        val basePtr = state.ptrOffset
                        val guard = state.readCellForControl(basePtr + op.summary.guardOffset)
                        if (guard is Const && validateLoopSummary(op.body, op.summary) { offset -> state.readCell(basePtr + offset) } == false) {
                            return false
                        }
                        if (!applyLoopSummary(state, basePtr, op.summary, guard)) return false
                    }

                    is AnalysisConditional -> {
                        val guard = state.readCellForControl(state.ptrOffset + op.offset)
                        when (guard) {
                            Const.ZERO -> {}
                            is Const -> if (!execute(op.body)) return false
                            else -> return false
                        }
                    }

                    is AnalysisLoop -> {
                        val basePtr = state.ptrOffset
                        when (val guard = state.readCellForControl(basePtr + op.offset)) {
                            Const.ZERO -> {}
                            else -> {
                                val summary = analyzeLoopAnalysis(op.body) { offset -> state.readRelative(basePtr, offset) } ?: return false
                                if (guard is Const && validateLoopSummary(op.body, summary) { offset -> state.readCell(basePtr + offset) } == false) {
                                    return false
                                }
                                if (!applyLoopSummary(state, basePtr, summary, guard)) return false
                            }
                        }
                    }
                }
            }
            return true
        }

        if (!execute(body)) return null

        val writes = state.writes()
        return Candidate(
            guardOffset = 0,
            pointerDelta = state.ptrOffset,
            writes = writes,
        )
    }

    private fun substituteOffsets(expr: Expr, mapping: Map<Int, Expr>): Expr = when (expr) {
        is Const -> expr
        is Cell -> mapping[expr.offset] ?: expr
        is GetTemp -> expr
        is Add -> expr.terms.fold(Const.ZERO as Expr) { acc, term -> acc + substituteOffsets(term, mapping) }
        is Mul -> expr.factors.fold(Const.ONE as Expr) { acc, factor -> acc * substituteOffsets(factor, mapping) }
        is Neg -> -substituteOffsets(expr.value, mapping)
        is ByteTruncate -> byte(substituteOffsets(expr.value, mapping))
        is ExactDiv -> substituteOffsets(expr.numerator, mapping) / expr.divisor
        is Choose -> choose(substituteOffsets(expr.value, mapping), expr.degree)
    }

    private fun toRecurrence(
        expr: Expr,
        solved: Map<Int, Recurrence>,
        modifiedOffsets: Set<Int>,
    ): Recurrence? = when (expr) {
        is Const -> Recurrence.constant(expr)
        is Cell -> when {
            expr.offset in solved -> solved.getValue(expr.offset)
            expr.offset in modifiedOffsets -> null
            else -> Recurrence.constant(expr)
        }

        is GetTemp -> null
        is Add -> expr.terms
            .map { toRecurrence(it, solved, modifiedOffsets) ?: return null }
            .fold(Recurrence.constant(Const.ZERO)) { acc, recurrence -> acc + recurrence }

        is Neg -> -(toRecurrence(expr.value, solved, modifiedOffsets) ?: return null)
        is ByteTruncate -> {
            val value = toRecurrence(expr.value, solved, modifiedOffsets) ?: return null
            if (!value.isConstant) return null
            Recurrence.constant(byte(value.constantTerm))
        }
        is Mul -> multiplyRecurrences(expr.factors.map { toRecurrence(it, solved, modifiedOffsets) ?: return null })

        is ExactDiv -> {
            val numerator = toRecurrence(expr.numerator, solved, modifiedOffsets) ?: return null
            if (!numerator.isConstant) return null
            Recurrence.constant(numerator.constantTerm / expr.divisor)
        }

        is Choose -> {
            val value = toRecurrence(expr.value, solved, modifiedOffsets) ?: return null
            chooseRecurrence(value, expr.degree)
        }
    }

    private fun multiplyRecurrences(factors: List<Recurrence>): Recurrence {
        if (factors.isEmpty()) return Recurrence.constant(Const.ONE)

        var product = Recurrence.constant(Const.ONE)
        for (factor in factors) {
            product *= factor
        }
        return product
    }

    private fun chooseRecurrence(value: Recurrence, degree: Int): Recurrence? {
        if (degree == 0) return Recurrence.constant(Const.ONE)
        if (degree == 1) return value
        if (value.isConstant) return Recurrence.constant(choose(value.constantTerm, degree))
        if (value.degree > 1) return null

        val samples = MutableList(degree + 1) { index ->
            choose(value.at(Const(index)), degree)
        }
        return recurrenceFromSamples(samples)
    }

    private fun recurrenceFromSamples(samples: List<Expr>): Recurrence {
        if (samples.isEmpty()) return Recurrence.constant(Const.ZERO)

        var layer = samples
        val coeffs = mutableListOf<Expr>()
        while (layer.isNotEmpty()) {
            coeffs += layer.first()
            layer = layer.zipWithNext { left, right -> right + -left }
        }
        return Recurrence(coeffs).trimmed()
    }

    private fun binomialProductFactor(leftDegree: Int, rightDegree: Int, overlap: Int): Int {
        val top = factorial(leftDegree + rightDegree - overlap)
        val bottom = factorial(overlap) *
            factorial(leftDegree - overlap) *
            factorial(rightDegree - overlap)
        return exactDivide(top, bottom).toInt()
    }

    private fun factorial(value: Int): Long {
        var result = 1L
        for (i in 2..value) {
            result *= i.toLong()
        }
        return result
    }

    private fun modInverse(a: Int, m: Int): Int? {
        val a0 = ((a % m) + m) % m
        if (a0 == 0) return null

        var oldR = a0
        var r = m
        var oldS = 1
        var s = 0

        while (r != 0) {
            val q = oldR / r

            val tmpR = r
            r = oldR - q * r
            oldR = tmpR

            val tmpS = s
            s = oldS - q * s
            oldS = tmpS
        }

        if (oldR != 1) return null
        return ((oldS % m) + m) % m
    }
}
