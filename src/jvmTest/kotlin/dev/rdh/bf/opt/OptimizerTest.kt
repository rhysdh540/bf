package dev.rdh.bf.opt

import dev.rdh.bf.Add
import dev.rdh.bf.Cell
import dev.rdh.bf.Choose
import dev.rdh.bf.Const
import dev.rdh.bf.MovePtr
import dev.rdh.bf.Mul
import dev.rdh.bf.Op
import dev.rdh.bf.Read
import dev.rdh.bf.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OptimizerTest {
    @Test
    fun `clear loop becomes single write summary`() {
        val summary = Optimizer.analyzeLoop(
            listOf(Store(0, Add(Cell(0), Const(-1))))
        )

        assertEquals(
            Optimizer.LoopSummary(
                guardOffset = 0,
                tripCount = Cell(0),
                pointerDelta = 0,
                writes = listOf(
                    Optimizer.LoopWrite(0, Const(0))
                ),
            ),
            summary
        )
    }

    @Test
    fun `transfer loop summary keeps guard write last`() {
        val summary = Optimizer.analyzeLoop(
            listOf<Op>(
                Store(0, Add(Cell(0), Const(-1))),
                MovePtr(1),
                Store(0, Add(Cell(0), Const(1))),
                MovePtr(-1),
            )
        )

        assertEquals(
            Optimizer.LoopSummary(
                guardOffset = 0,
                tripCount = Cell(0),
                pointerDelta = 0,
                writes = listOf(
                    Optimizer.LoopWrite(1, Add(Cell(1), Cell(0))),
                    Optimizer.LoopWrite(0, Const(0)),
                ),
            ),
            summary
        )
    }

    @Test
    fun `multiple target writes are ordered before the guard`() {
        val summary = Optimizer.analyzeLoop(
            listOf<Op>(
                Store(0, Add(Cell(0), Const(-1))),
                MovePtr(1),
                Store(0, Add(Cell(0), Const(1))),
                MovePtr(1),
                Store(0, Add(Cell(0), Const(2))),
                MovePtr(-2),
            )
        )

        assertEquals(
            listOf(
                Optimizer.LoopWrite(1, Add(Cell(1), Cell(0))),
                Optimizer.LoopWrite(2, Add(Cell(2), Mul(Const(2), Cell(0)))),
                Optimizer.LoopWrite(0, Const(0)),
            ),
            summary?.writes
        )
    }

    @Test
    fun `split summary carries a peeled prologue when invariants appear after one iteration`() {
        val summary = Optimizer.analyzeLoop(
            listOf<Op>(
                Store(4, Add(Cell(4), Cell(1))),
                Store(1, Const(7)),
                Store(0, Add(Cell(0), Const(-1))),
            )
        )

        assertEquals(
            Optimizer.LoopSummary(
                guardOffset = 0,
                tripCount = Cell(0),
                pointerDelta = 0,
                prologue = listOf(
                    Optimizer.LoopWrite(0, Add(Cell(0), Const(-1))),
                    Optimizer.LoopWrite(4, Add(Cell(4), Cell(1))),
                    Optimizer.LoopWrite(1, Const(7)),
                ),
                writes = listOf(
                    Optimizer.LoopWrite(4, Add(Cell(4), Mul(Const(7), Cell(0)))),
                    Optimizer.LoopWrite(0, Const(0)),
                ),
            ),
            summary
        )
    }

    @Test
    fun `triangular recurrences lower to choose terms`() {
        val summary = Optimizer.analyzeLoop(
            listOf<Op>(
                Store(1, Add(Cell(1), Cell(2))),
                Store(2, Add(Cell(2), Const(1))),
                Store(0, Add(Cell(0), Const(-1))),
            )
        )

        assertEquals(
            Optimizer.LoopSummary(
                guardOffset = 0,
                tripCount = Cell(0),
                pointerDelta = 0,
                writes = listOf(
                    Optimizer.LoopWrite(1, Add(Cell(1), Mul(Cell(2), Cell(0)), Choose(Cell(0), 2))),
                    Optimizer.LoopWrite(2, Add(Cell(2), Cell(0))),
                    Optimizer.LoopWrite(0, Const(0)),
                ),
            ),
            summary
        )
    }

    @Test
    fun `entry facts unlock the triangular inner loop`() {
        val summary = Optimizer.analyzeLoop(
            listOf<Op>(
                Store(3, Add(Cell(3), Cell(1), Cell(0))),
                Store(0, Add(Cell(2), Cell(0), Const(-1))),
                Store(1, Const(0)),
                Store(2, Const(0)),
            )
        ) { offset ->
            when (offset) {
                1, 2 -> Const.ZERO
                else -> Cell(offset)
            }
        }

        assertEquals(
            Optimizer.LoopSummary(
                guardOffset = 0,
                tripCount = Cell(0),
                pointerDelta = 0,
                writes = listOf(
                    Optimizer.LoopWrite(3, Add(Cell(3), Mul(Cell(0), Cell(0)), Mul(Const(-1), Choose(Cell(0), 2)))),
                    Optimizer.LoopWrite(0, Const(0)),
                ),
            ),
            summary
        )
    }

    @Test
    fun `non unit odd guard delta uses modular inverse trip count`() {
        val summary = Optimizer.analyzeLoop(
            listOf(Store(0, Add(Cell(0), Const(3))))
        )

        assertEquals(
            Mul(Const(85), Cell(0)),
            summary?.tripCount
        )
    }

    @Test
    fun `unsupported loops return no summary`() {
        assertNull(Optimizer.analyzeLoop(emptyList()))
        assertNull(Optimizer.analyzeLoop(listOf(Store(0, Add(Cell(0), Const(2))))))
        assertNull(Optimizer.analyzeLoop(listOf(Read(0))))
    }
}
