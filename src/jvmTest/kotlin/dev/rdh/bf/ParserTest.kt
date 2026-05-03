package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParserTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

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
    fun `nested loops reduce to single constant output`() {
        val program = ">>+++++++>>>>>>->->--------[-<[-]-[-<[-]-[-<<<<<<<<[-]-[->[-]>>[-]>>[-]<<<[-<+>>+<]>[-<+>]<<[->>>+>+<<<<]>>>>[-<<<<+>>>>]<<<<<]>>>>>>>>]>]>]<<<<<<."
        val ops = Parser.parse(program)

        assertEquals(listOf(Write(Const(56))), ops)
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
    fun `triangular example folds to a single constant write`() {
        val ops = Parser.parse(resource("triangular.b"))
        assertEquals(listOf(Write(Const(65))), ops)
    }

    @Test
    fun `long example preserves its output byte`() {
        val output = ByteOutput()
        Interpreter.run(Parser.parse(resource("long.b")), tapeSize = 1 shl 15, input = NullInput, output = output)
        assertEquals(listOf(202), output.bytes)
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
