package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParserTest {
    @Test
    fun `adjacent pointer moves are merged`() {
        val ops = Parser.parse(">><<>")
        assertEquals(listOf(MovePtr(1)), ops)
    }

    @Test
    fun `net zero pointer motion is dropped`() {
        val ops = Parser.parse(">><<><")
        assertEquals(emptyList(), ops)
    }

    @Test
    fun `adjacent increments on the same cell are merged`() {
        val ops = Parser.parse("++-")
        assertEquals(listOf(Store(0, Cell(0) + Const(1))), ops)
    }

    @Test
    fun `empty loops are dropped at parse time`() {
        val ops = Parser.parse("[]")
        assertEquals(emptyList(), ops)
    }

    @Test
    fun `closed loops are appended through the parent merge path`() {
        val ops = Parser.parse(">[]>")
        assertEquals(listOf(MovePtr(2)), ops)
    }

    @Test
    fun `unexpected close bracket throws`() {
        val ex = assertFailsWith<IllegalStateException> {
            Parser.parse("]")
        }
        assertEquals("Unexpected ]", ex.message)
    }

    @Test
    fun `unclosed open bracket throws`() {
        val ex = assertFailsWith<IllegalStateException> {
            Parser.parse("[")
        }
        assertEquals("Unclosed [", ex.message)
    }
}
