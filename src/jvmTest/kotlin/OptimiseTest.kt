import dev.rdh.bf.opt.bfOptimise
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
        val expected = listOf<dev.rdh.bf.BFOperation>()

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }

    @Test
    fun edgeCase() {
        val program = ">>>>>-"
        val expected = listOf<dev.rdh.bf.BFOperation>()

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
}
