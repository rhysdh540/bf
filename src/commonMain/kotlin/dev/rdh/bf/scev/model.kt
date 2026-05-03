package dev.rdh.bf.scev

data class LoopId(val value: Int)

/**
 * scalar-evolution IR for loop reasoning
 */
sealed interface Expr

data class Const(val value: Int) : Expr

data class EntryCell(val loop: LoopId, val offset: Int) : Expr

data class TripCount(val loop: LoopId) : Expr

data class Add(val terms: List<Expr>) : Expr

data class Mul(val factors: List<Expr>) : Expr

data class Neg(val value: Expr) : Expr

/**
 * LLVM-style add recurrence: `{start, +, step}_loop`.
 *
 * The step may itself be another [AddRec], which is how higher-degree polynomials naturally
 * arise. For example, `for (int i = n; ; i++)` is `{n, +, 1}`, and a sum that
 * adds `i` each trip is `{0, +, {n, +, 1}}`.
 */
data class AddRec(val loop: LoopId, val start: Expr, val step: Expr) : Expr

/**
 * Integer-valued binomial basis term `C(value, degree)`. This is the canonical closed form for
 * repeated summation, since `sum_{t=0}^{n-1} C(t, d) = C(n, d + 1)`.
 */
data class Choose(val value: Expr, val degree: Int) : Expr

/**
 * Summary for one cell touched by a loop.
 *
 * [evolution] describes the value as a function of the loop iteration, while [exitValue]
 * substitutes the loop trip count into that evolution.
 */
data class CellSummary(
    val evolution: Expr,
    val exitValue: Expr,
)

data class LoopSummary(
    val loop: LoopId,
    val tripCount: Expr,
    val guardOffset: Int,
    val pointerDelta: Int,
    val cells: Map<Int, CellSummary>,
)
