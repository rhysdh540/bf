import bf.opt.bfOptimise
import bf.bfParse
import bf.bfProgram
import bf.opt.bfStrip
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
            increment(6, offset = 2)
            increment(7, offset = 3)
            moveRight(3)
        }

        val optimised = bfOptimise(bfParse(program))

        assertEquals(expected, optimised)
    }

    @Test
    fun testZero() {
        val program = "+++[-]."
        val expected = bfProgram {
            increment(3)
            setToZero()
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
            increment(4)
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
        assertEquals(listOf(), bfStrip(bfOptimise(bfParse(program))))
    }

    @Test
    fun testCopyLoop() {
        val program = "[->++>>+<<<]"
        val expected = bfProgram {
            copy(1 to 2, 3 to 1)
        }

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }

    @Test
    fun edgeCase() {
        val program = ">>>>>-"
        val expected = bfProgram {
            moveRight(5)
            decrement()
        }

        val optimised = bfOptimise(bfParse(program))
        assertEquals(expected, optimised)
    }
}