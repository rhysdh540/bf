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

    @Test
    fun `byte truncation stays explicit for symbolic values`() {
        assertEquals(Const(1), byte(Const(257)))
        assertEquals(Cell(4), byte(Cell(4)))
        assertEquals(Add(Cell(0), Const(257)), byte(Add(Cell(0), Const(257))))
        assertEquals(ByteTruncate(Choose(Cell(0), 2)), byte(Choose(Cell(0), 2)))
    }
}
