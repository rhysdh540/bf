package dev.rdh.bf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InterpreterTest {
    @Test
    fun `store and write truncate to byte`() {
        val output = runProgram(
            Store(0, Const(-1)),
            Store(1, Const(258)),
            Write(Cell(0)),
            Write(Cell(1)),
            Write(Const(513)),
        )

        assertEquals(listOf(255, 2, 1), output.bytes)
    }

    @Test
    fun `read truncates input bytes and eof becomes 255`() {
        val output = runProgram(
            Read(0),
            Read(1),
            Write(Cell(0)),
            Write(Cell(1)),
            input = intArrayOf(300, -1),
        )

        assertEquals(listOf(44, 255), output.bytes)
    }

    @Test
    fun `temps preserve snapshot values across later stores`() {
        val t0 = Temp(0)
        val output = runProgram(
            Store(0, Const(5)),
            SetTemp(t0, Cell(0)),
            Store(0, Const(9)),
            Store(1, Add(listOf(GetTemp(t0), Const(1)))),
            Write(Cell(0)),
            Write(Cell(1)),
        )

        assertEquals(listOf(9, 6), output.bytes)
    }

    @Test
    fun `temps persist across nested loop execution`() {
        val t0 = Temp(0)
        val output = runProgram(
            SetTemp(t0, Const(2)),
            Store(0, Const(2)),
            Loop(0, listOf(
                Store(1, Add(listOf(Cell(1), GetTemp(t0)))),
                Store(0, Add(listOf(Cell(0), Const(-1)))),
            )),
            Write(Cell(1)),
        )

        assertEquals(listOf(4), output.bytes)
    }

    @Test
    fun `move pointer and relative offsets address the expected cells`() {
        val output = runProgram(
            Store(0, Const(7)),
            MovePtr(1),
            Store(0, Const(11)),
            MovePtr(-1),
            Write(Cell(0)),
            Write(Cell(1)),
            MovePtr(1),
            Write(Cell(0)),
            Write(Cell(-1)),
        )

        assertEquals(listOf(7, 11, 11, 7), output.bytes)
    }

    @Test
    fun `conditional only executes when guard cell is nonzero`() {
        val output = runProgram(
            Store(1, Const(0)),
            Conditional(1, listOf(
                MovePtr(2),
                Store(0, Const(99)),
            )),
            Write(Cell(0)),
            Store(1, Const(3)),
            Conditional(1, listOf(
                Store(0, Const(42)),
            )),
            Write(Cell(0)),
        )

        assertEquals(listOf(0, 42), output.bytes)
    }

    @Test
    fun `loop guard offset is relative to current pointer`() {
        val output = runProgram(
            Store(0, Const(40)),
            Store(1, Const(2)),
            Loop(1, listOf(
                Store(1, Add(listOf(Cell(1), Const(-1)))),
                Store(0, Add(listOf(Cell(0), Const(1)))),
            )),
            Write(Cell(0)),
            Write(Cell(1)),
        )

        assertEquals(listOf(42, 0), output.bytes)
    }

    @Test
    fun `nested loop can implement repeated addition`() {
        val output = runProgram(
            Store(0, Const(3)),
            Store(1, Const(2)),
            Loop(0, listOf(
                Store(2, Add(listOf(Cell(2), Cell(1)))),
                Store(0, Add(listOf(Cell(0), Const(-1)))),
            )),
            Write(Cell(2)),
        )

        assertEquals(listOf(6), output.bytes)
    }

    @Test
    fun `choose and div evaluate exact arithmetic expressions`() {
        val output = runProgram(
            Write(ExactDiv(Const(42), 7)),
            Write(Choose(Const(5), 2)),
            Write(Choose(Const(5), 3)),
            Write(Choose(Const(-3), 2)),
            Write(Choose(Const(99), 0)),
        )

        assertEquals(listOf(6, 10, 10, 6, 1), output.bytes)
    }

    @Test
    fun `div rejects inexact division`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runProgram(
                Write(ExactDiv(Const(5), 2)),
            )
        }

        assertEquals("inexact division: 5 / 2", ex.message)
    }

    @Test
    fun `choose rejects negative degree`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runProgram(
                Write(Choose(Const(5), -1)),
            )
        }

        assertEquals("negative choose degree: -1", ex.message)
    }

    @Test
    fun `runner flushes output and compiled executable is reusable`() {
        val executable = Interpreter.compile(
            listOf(
                Store(0, Const(1)),
                Write(Cell(0)),
            ),
            tapeSize = 16,
        )

        val first = ByteOutput()
        val second = ByteOutput()
        executable.run(NullInput, first)
        executable.run(NullInput, second)

        assertEquals(listOf(1), first.bytes)
        assertEquals(listOf(1), second.bytes)
        assertEquals(1, first.flushes)
        assertEquals(1, second.flushes)
    }

    private fun runProgram(vararg ops: Op, input: IntArray = intArrayOf(), tapeSize: Int = 32): ByteOutput {
        val output = ByteOutput()
        Interpreter.run(ops.asList(), tapeSize, ByteInput(input), output)
        return output
    }
}
