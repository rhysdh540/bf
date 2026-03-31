package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParserTest {
    @Test
    fun `solveLoopBody emits product terms when loop deltas are invariant`() {
        val solved = Parser.trySolveLoop(
            listOf(
                writeBlockOf(
                    Write(0, Expression.cell(0) + Expression.const(-1)),
                    Write(1, Expression.cell(1) + Expression.cell(2)),
                )
            )
        )

        val block = assertIs<WriteBlock>(solved)
        val writesByOffset = block.writes.associateBy { it.offset }
        assertEquals(Expression.ZERO, writesByOffset.getValue(0).expr)
        assertEquals(
            Expression.cell(1) + Expression.cell(2) * Expression.cell(0),
            writesByOffset.getValue(1).expr
        )
    }

    @Test
    fun `solveLoopBody split-solves when a peeled iteration exposes invariant polynomial expressions`() {
        val solved = Parser.trySolveLoop(
            listOf(
                writeBlockOf(
                    Write(0, Expression.cell(0) + Expression.const(-1)),
                    Write(1, Expression.cell(2) * Expression.cell(3)),
                    Write(4, Expression.cell(4) + Expression.cell(1)),
                )
            )
        )

        val cond = assertIs<Conditional>(solved)
        assertEquals(2, cond.body.size)

        val remainder = assertIs<WriteBlock>(cond.body[1])
        val writesByOffset = remainder.writes.associateBy { it.offset }
        assertEquals(Expression.ZERO, writesByOffset.getValue(0).expr)
        assertFalse(1 in writesByOffset)
        assertEquals(
            Expression.cell(4) + (Expression.cell(2) * Expression.cell(3)) * Expression.cell(0),
            writesByOffset.getValue(4).expr
        )
    }

    @Test
    fun `solveLoopBody still split-solves constant invariants`() {
        val solved = Parser.trySolveLoop(
            listOf(
                writeBlockOf(
                    Write(0, Expression.cell(0) + Expression.const(-1)),
                    Write(1, Expression.const(7)),
                    Write(4, Expression.cell(4) + Expression.cell(1)),
                )
            )
        )

        val cond = assertIs<Conditional>(solved)
        val remainder = assertIs<WriteBlock>(cond.body[1])
        val writesByOffset = remainder.writes.associateBy { it.offset }
        assertFalse(1 in writesByOffset)
        assertEquals(
            Expression.cell(4) + Expression.const(7) * Expression.cell(0),
            writesByOffset.getValue(4).expr
        )
    }

    @Test
    fun `solveLoopBody treats identity-preserved helper cells as invariant`() {
        val solved = Parser.trySolveLoop(
            listOf(
                writeBlockOf(
                    Write(0, Expression.cell(0) + Expression.const(-1)),
                    Write(1, Expression.cell(5) + Expression.cell(2)),
                    Write(2, Expression.cell(3) + Expression.cell(2)),
                    Write(3, Expression.ZERO),
                    Write(4, Expression.cell(4) + Expression.cell(2)),
                    Write(5, Expression.ZERO),
                )
            )
        )

        val cond = assertIs<Conditional>(solved)
        val remainder = assertIs<WriteBlock>(cond.body[1])
        val writesByOffset = remainder.writes.associateBy { it.offset }
        assertEquals(Expression.ZERO, writesByOffset.getValue(0).expr)
        assertFalse(1 in writesByOffset)
        assertFalse(2 in writesByOffset)
        assertFalse(3 in writesByOffset)
        assertFalse(5 in writesByOffset)
        assertEquals(
            Expression.cell(4) + Expression.cell(2) * Expression.cell(0),
            writesByOffset.getValue(4).expr
        )
    }

    @Test
    fun `nested_loops reduces to single constant output`() {
        val program = ">>+++++++>>>>>>->->--------[-<[-]-[-<[-]-[-<<<<<<<<[-]-[->[-]>>[-]>>[-]<<<[-<+>>+<]>[-<+>]<<[->>>+>+<<<<]>>>>[-<<<<+>>>>]<<<<<]>>>>>>>>]>]>]<<<<<<."
        val ops = Parser.parse(program)

        assertEquals(1, ops.size)
        val ioBlock = assertIs<IOBlock>(ops.single())
        assertEquals(1, ioBlock.ops.size)
        val output = assertIs<Output>(ioBlock.ops.single())
        assertEquals(Expression.const(56), output.expr)
        assertEquals(0, ioBlock.pointerDelta)
    }

    @Test
    fun `dead writes are eliminated at end of program`() {
        // +++. should produce IOBlock with Output(3), no trailing WriteBlock
        val ops = Parser.parse("+++.")
        assertEquals(1, ops.size)
        val ioBlock = assertIs<IOBlock>(ops.single())
        assertEquals(1, ioBlock.ops.size)
        val output = assertIs<Output>(ioBlock.ops.single())
        assertEquals(Expression.const(3), output.expr)
    }

    @Test
    fun `conditional is inlined when guard is known nonzero`() {
        // +[-] should reduce to empty (cell set to 1, then loop zeros it, dead write eliminated)
        val ops = Parser.parse("+[-]")
        assertTrue(ops.isEmpty())
    }

    private fun writeBlockOf(vararg writes: Write) = WriteBlock(
        pointerDelta = 0,
        writes = writes.toList(),
        workingOffset = 0,
    )
}
