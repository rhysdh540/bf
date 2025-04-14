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
            moveRight()
            increment(6)
            moveLeft(6)
            loop {
                decrement()
                moveRight(2)
                increment(6)
                moveLeft()
            }
            moveRight(2)
            increment(6)
            moveRight()
            increment(7)
        }

        val parsed = bfParse(program)
        val optimised = bfOptimise(parsed)

        assertEquals(expected, optimised)
        assertEquals(bfOptimise(optimised), optimised)
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
}