import bf.opt.bfOptimise
import bf.bfParse
import bf.bfRun
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgramTest {
    @Test
    fun `test hello world`() {
        val program = getResource("hello_world.b")

        val output = programBench(program)
        assertEquals("Hello World!\n", output)
    }

    @Test
    fun `test squares`() {
        val program = getResource("squares.b")

        val output = programBench(program)
        val expected = (0..100).joinToString("\n") { (it * it).toString() } + "\n"

        assertEquals(expected, output)
    }
}

private fun getResource(name: String): String {
    return ProgramTest::class.java.classLoader.getResourceAsStream(name)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: throw IllegalStateException("File not found")
}

private fun programBench(program: String): String {
    val parsed = bfParse(program)
    val optimised = bfOptimise(parsed)
    val output = StringWriter()
    bfRun(optimised, stdout = output)
    return output.toString()
}