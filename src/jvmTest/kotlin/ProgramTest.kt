import dev.rdh.bf.NullInput
import dev.rdh.bf.NullOutput
import dev.rdh.bf.StringOutput
import dev.rdh.bf.SystemRunnerOptions
import dev.rdh.bf.bfCompile
import dev.rdh.bf.opt.bfOptimise
import dev.rdh.bf.bfParse
import dev.rdh.bf.bfRun
import java.io.Reader.nullReader
import java.io.Writer.nullWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTime

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

fun main() {
    val program = bfParse(getResource("mandelbrot.b"))
        .let { bfOptimise(it) }

    val runs = 1

    val interpretedTime = measureTime {
        repeat(runs) {
            bfRun(
                program,
                stdin = NullInput,
                stdout = NullOutput,
            )
        }
    }

    println("Interpreted time: ${formatTime(interpretedTime.div(runs))}")

    val compiled = bfCompile(program, SystemRunnerOptions(export = true))

    val jitTime = measureTime {
        repeat(runs) {
            compiled(nullReader(), nullWriter())
        }
    }

    println("JIT time: ${formatTime(jitTime.div(runs))}")
}

private fun formatTime(time: kotlin.time.Duration): String {
    val seconds = time.inWholeSeconds
    val milliseconds = time.inWholeMilliseconds % 1000
    return if (seconds > 0) {
        "${seconds}.${milliseconds}s"
    } else {
        "${milliseconds}ms"
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
    val output = StringOutput()
    bfRun(
        optimised,
        stdout = output,
        stdin = NullInput,
    )
    return output.toString()
}
