import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.Loop
import dev.rdh.bf.PointerMove
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange
import dev.rdh.bf.opt.bfOptimise
import dev.rdh.bf.opt.CopyLoopReplacer
import dev.rdh.bf.bfParse
import dev.rdh.bf.bfProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class OptimiseTest {
    @Test
    fun testMerging() {
        val program = "++++>++++++<<<<<<[->>++++++<]>>++++++>+++++++"
        val expected = bfProgram {
            increment(4)
            increment(6, offset = 1)
            moveLeft(5)
            loop {
                decrement()
                increment(6, offset = 2)
                moveRight()
            }
        }

        val optimised = bfOptimise(bfParse(program))

        assertEquals(expected, optimised)
    }

    @Test
    fun testZero() {
        val program = "+++[-]."
        val expected = bfProgram {
            print()
        }

        val parsed = bfParse(program)
        val optimised = bfOptimise(parsed)

        assertEquals(expected, optimised)
    }

    @Test
    fun testNonZero() {
        val program = "++++[-]+++[.-]"
        val expected = bfProgram {
            set(3u)
            loop {
                print()
                decrement()
            }
        }

        val optimised = bfOptimise(bfParse(program))

        assertEquals(expected, optimised)
    }

    @Test
    fun testStripping() {
        val program = "++++++>++++++[-]>++++++"
        assertEquals(listOf(), bfOptimise(bfParse(program)))
    }

    @Test
    fun testCopyLoop() {
        val program = "[->++>>+<<<]"
        val expected = listOf<BFOperation>()

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }

    @Test
    fun edgeCase() {
        val program = ">>>>>-"
        val expected = listOf<BFOperation>()

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }

    @Test
    fun testSetThenAddThenSet() {
        val program = "[-]++++[-]."
        val expected = bfProgram {
            print()
        }

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }

    @Test
    fun testGeneralizedCopyLoopWithOddInductionDelta() {
        val loop = Loop(
            ValueChange(-3),
            PointerMove(1),
            ValueChange(1),
            PointerMove(-1),
        )

        val program = mutableListOf<BFOperation>(loop)
        CopyLoopReplacer.run(program)

        assertEquals(
            listOf(
                Copy(multiplier = -85, offset = 1),
                SetToConstant(),
            ),
            program,
        )
    }

    @Test
    fun testGeneralizedCopyLoopCanEliminateInductionOnlyLoop() {
        val loop = Loop(ValueChange(3))
        val program = mutableListOf<BFOperation>(loop)

        CopyLoopReplacer.run(program)

        assertEquals(
            listOf<BFOperation>(SetToConstant()),
            program,
        )
    }

    @Test
    fun testGeneralizedCopyLoopSkipsNonInvertibleInductionDelta() {
        val loop = Loop(
            ValueChange(-2),
            PointerMove(1),
            ValueChange(1),
            PointerMove(-1),
        )

        val program = mutableListOf<BFOperation>(loop)
        CopyLoopReplacer.run(program)

        assertEquals(listOf<BFOperation>(loop), program)
    }
}
