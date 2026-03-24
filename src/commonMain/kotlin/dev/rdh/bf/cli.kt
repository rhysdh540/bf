package dev.rdh.bf

import kotlin.time.measureTime
import kotlin.time.measureTimedValue

abstract class CommandLine {
    protected abstract fun readFile(path: String): String

    protected abstract val stdin: BfInput
    protected abstract val stdout: BfOutput
    protected abstract val stderr: BfOutput

    protected abstract val nativeCodeType: String
    protected abstract val systemRunner: BfRunner?

    private var compiled = false
    private var export = false
    private var printTime = false
    private var nextIsString = false

    private val options: List<Option> by lazy {
        listOf(
            Option("help", 'h', "Show this help message") {
                stderr.write("Usage: bf [options] <program.b>\n")
                stderr.write("Options:\n")
                for (option in options) {
                    stderr.write("       --${option.name}")
                    if (option.short != null) {
                        stderr.write(", -${option.short}")
                    }
                    stderr.write("\n               ${option.description}\n")
                }
            },
            Option("compile", 'c', "Compile the following programs to $nativeCodeType") { compiled = true },
            Option("interpret", 'i', "Run the following programs in interpreted mode (default)") { compiled = false },
            Option("export", 'E', "Export the following programs to a file in `.bf.out` (if --compiled)") { export = true },
            Option("no-export", null, "Do not export the following programs") { export = false },
            Option("time", 't', "Print the time taken to execute the following programs") { printTime = true },
            Option("eval", 'e', "Evaluate the next argument directly as a Brainfuck program") { nextIsString = true }
        )
    }

    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            options.first { it.name == "help" }.action()
            return
        }
        for (arg in args) {
            if (nextIsString) {
                nextIsString = false
                exec(arg)
                continue
            }

            if (options.any { it.evaluate(arg) }) {
                continue
            }

            exec(readFile(arg))
        }
    }

    private fun exec(literal: String) {
        val program = Parser.parse(literal)

        val runner = if (compiled) {
            systemRunner ?: Interpreter
        } else {
            Interpreter
        }

        val (executable, cTime) = measureTimedValue { runner.compile(program, 1 shl 15) }

        val time = measureTime { executable.run(stdin, stdout) }

        if (printTime) {
            stderr.write("Compile time: ${formatTime(cTime)}\n")
            stderr.write("Execution time: ${formatTime(time)}\n")
        }
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
}

private data class Option(
    val name: String,
    val short: Char?,
    val description: String,
    val action: () -> Unit
) {
    fun evaluate(arg: String): Boolean {
        if (arg == "--$name" || (short != null && arg == "-$short")) {
            action()
            return true
        }
        return false
    }
}