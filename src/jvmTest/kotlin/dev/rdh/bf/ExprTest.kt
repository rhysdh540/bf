package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals

class ExprTest {
    @Test
    fun `operators canonicalize simple arithmetic`() {
        assertEquals(Const(3), Const(1) + Const(2))
        assertEquals(Cell(4), Cell(4) + Const.ZERO)
        assertEquals(Const.ZERO, Cell(4) * Const.ZERO)
        assertEquals(Cell(-2), -(-Cell(-2)))
    }

    @Test
    fun `exact constant division folds but inexact division stays explicit`() {
        assertEquals(Const(6), Const(42) / 7)
        assertEquals(ExactDiv(Const(5), 2), Const(5) / 2)
    }
}
