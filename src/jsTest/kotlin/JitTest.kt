import bf.NullInputProvider
import bf.StringOutput
import bf.bfCompile
import bf.bfProgram
import kotlin.test.Test
import kotlin.test.assertEquals

class JitTest {
    @Test
    fun testJitWorks() {
        val prog = bfProgram {
            increment(65)
            print()
        }
        val compiled = bfCompile(prog)
        val out = StringOutput()
        compiled(out, NullInputProvider)
        assertEquals("A", out.toString())
    }
}