import bf.bfParse
import bf.bfProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class ParseTest {
    @Test
    fun testParse() {
        val program = "+[->++<]"

        val expected = bfProgram {
            increment()
            loop {
                decrement()
                moveRight()
                increment()
                increment()
                moveLeft()
            }
        }

        val parsed = bfParse(program)
        assertEquals(parsed, expected)
    }

    @Test
    fun testParseWithStuff() {
        val program = " +[23->as fwwe e7f+t2 6+4< 6] "

        val expected = bfProgram {
            increment()
            loop {
                decrement()
                moveRight()
                increment()
                increment()
                moveLeft()
            }
        }

        val parsed = bfParse(program)
        assertEquals(parsed, expected)
    }
}