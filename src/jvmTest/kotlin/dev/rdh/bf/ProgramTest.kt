package dev.rdh.bf

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIsNot
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProgramTest {
    private fun resource(name: String) = ProgramTest::class.java.getResourceAsStream("/$name")
        ?.readAllBytes() ?: throw IllegalArgumentException("Resource not found: $name")

    val runTestsLong = System.getenv("RUN_TESTS_LONG") != null

    private interface Result {
        data object Timeout : Result
        data class Success(val output: String) : Result {
            override fun toString() = buildString {
                append("Success(")
                fun String.safe() = replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                    .replace("\b", "\\b")
                if (output.length > 20) {
                    append(output.safe().take(17)).append("...")
                } else {
                    append(output.safe())
                }
                append(')')
            }
        }
        data class Failure(val exception: Throwable) : Result
    }

    private fun runProgram(
        name: String,
        inputName: String? = null,
        timeout: Duration = 1.seconds,
    ): Result {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            val future = pool.submit<String> {
                val source = resource("$name.b").decodeToString()
                val program = Parser.parse(source)
                val inputBytes = inputName?.let {
                    resource(it)
                }?.map(Byte::toInt)?.toIntArray() ?: IntArray(0)
                val sink = ByteOutput()

                Interpreter.run(program, 1 shl 15, ByteInput(inputBytes), sink)

                sink.bytes.map(Int::toByte).toByteArray().toString(Charsets.ISO_8859_1)
            }

            Result.Success(future.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS))
        } catch (e: Exception) {
            when (e) {
                is java.util.concurrent.TimeoutException -> Result.Timeout
                else -> Result.Failure(e)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `hello world`() {
        assertEquals(Result.Success("Hello World!\n"), runProgram("hello_world"))
    }

    @Test
    fun squares() {
        val expected = (0..100).joinToString("\n") { "${it * it}" }
        assertEquals(Result.Success(expected + "\n"), runProgram("squares"))
    }

    @Test
    fun `twinkle twinkle little star`() {
        val expected = """
            Twinkle, twinkle, little star,
            How I wonder what you are.
            Up above the world so high,
            Like a diamond in the sky.
            Twinkle, twinkle, little star,
            How I wonder what you are!

            When the blazing sun is gone,
            When there's nothing he shines upon,
            Then you show your little light,
            Twinkle, twinkle, through the night.
            Twinkle, twinkle, little star,
            How I wonder what you are!

            In the dark blue sky so deep
            Through my curtains often peep
            For you never close your eyes
            Til the morning sun does rise
            Twinkle, twinkle, little star
            How I wonder what you are

            Twinkle, twinkle, little star
            How I wonder what you are${' '}
        """.trimIndent()
        assertEquals(Result.Success(expected), runProgram("twinkle"))
    }

    @Test
    fun decode() {
        val expected = """
            CONNECTION ESTABLISHED.
            RESOLVED PEERS.
            VALIDATED SESSION.
            STANDBY FOR FURTHER BROADCASTS.
            
        """.trimIndent()
        assertEquals(Result.Success(expected), runProgram("decode", "decode.in"))
    }

    @Test
    fun bottles() {
        val expected = (99 downTo 1).joinToString("\n") {
            val p = if (it == 1) "" else "s"
            val p2 = if (it == 2) "" else "s"
            """
                $it Bottle$p of beer on the wall
                $it Bottle$p of beer
                Take one down and pass it around
                ${it - 1} Bottle$p2 of beer on the wall
                
            """.trimIndent()
        }.replace("\n", "\r\n") + "\r\n"

        assertEquals(Result.Success(expected), runProgram("bottles", timeout = 500.milliseconds))
    }

    @Test
    fun factor() {
        assertEquals(
            Result.Success("133333333333337: 397 1279 262589699\n"),
            runProgram("factor", "factor.in", 6.seconds)
        )
    }

    @Test
    fun `nested loops`() {
        val result = runProgram("nested_loops", timeout = 500.milliseconds)
        if (result !is Result.Timeout || runTestsLong) {
            assertEquals(Result.Success("8"), result)
        }
    }

    @Test
    fun triangular() {
        val result = runProgram("triangular", timeout = 500.milliseconds)
        if (result !is Result.Timeout || runTestsLong) {
            assertEquals(Result.Success("A"), result)
        }
    }

    @Test
    fun mandelbrot() {
        val result = runProgram("mandelbrot", timeout = 1000.milliseconds)
        if (result !is Result.Timeout || runTestsLong) {
            assertEquals(
                Result.Success(resource("mandelbrot.out").decodeToString()),
                result
            )
        }
    }
}