package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParserTest {
    @Test
    fun `straight-line motion and arithmetic are folded into constant output`() {
        val ops = Parser.parse(">><<>+.")
        assertEquals(listOf(Write(Const(1))), ops)
    }

    @Test
    fun `empty and dead leading loops are eliminated`() {
        assertEquals(emptyList(), Parser.parse("[]"))
        assertEquals(emptyList(), Parser.parse("[-]"))
    }

    @Test
    fun `clear loops fold away once their end-state is dead`() {
        val ops = Parser.parse(">[-]<+.")
        assertEquals(listOf(Write(Const(1))), ops)
    }

    @Test
    fun `simple transfer loops fold through to observable output`() {
        val ops = Parser.parse("+++[->+<]>.")
        assertEquals(listOf(Write(Const(3))), ops)
    }

    @Test
    fun `unsupported loops are preserved when their guard may be live`() {
        val ops = Parser.parse("+[++]>+.")
        assertEquals(
            listOf(
                Store(0, Const(1)),
                Loop(0, listOf(Store(0, Cell(0) + Const(2)))),
                Write(Cell(1) + Const(1)),
            ),
            ops
        )
    }

    @Test
    fun `nested loops still reduce to a final write`() {
        val program = ">>+++++++>>>>>>->->--------[-<[-]-[-<[-]-[-<<<<<<<<[-]-[->[-]>>[-]>>[-]<<<[-<+>>+<]>[-<+>]<<[->>>+>+<<<<]>>>>[-<<<<+>>>>]<<<<<]>>>>>>>>]>]>]<<<<<<."
        val ops = Parser.parse(program)

        assertEquals(Write(Cell(-6)), ops.last())
    }

    @Test
    fun `conditional is inlined when guard is known nonzero`() {
        assertEquals(emptyList(), Parser.parse("+[-]"))
    }

    @Test
    fun `leading dead loop does not block later constant folding`() {
        val ops = Parser.parse("[-]+++.")
        assertEquals(listOf(Write(Const(3))), ops)
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
