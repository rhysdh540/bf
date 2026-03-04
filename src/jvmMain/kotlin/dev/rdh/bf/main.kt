@file:JvmName("Main")

package dev.rdh.bf

import dev.rdh.bf.opt.bfOptimise
import dev.rdh.bf.opt.bfStrip
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.time.measureTime

@OptIn(ExperimentalPathApi::class)
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("""
            |Usage: bf.jar <program.b>
            |Options:
            |       --help, -h
            |               Show this help message
            |       --overflow-protection, -o
            |               Enable overflow protection for the following programs
            |       --optimise, -O
            |               Optimise the intermediate representation of the following programs
            |       --strip, -S
            |               Strip unused code from the following programs
            |       --compile, -c
            |               Compile the following programs to java bytecode
            |       --interpreted, -i
            |               Run the following programs in interpreted mode (default)
            |       --eval, -e
            |               Evaluate the next argument directly as a string
            |       --export, -E
            |               Export the following programs to a file in `.bf.out`
            |       --time, -t
            |               Time the following programs
            |       --no-export
            |               Do not export the following programs
        """.trimMargin())
        return
    }

    Path(".bf.out").deleteRecursively()

    var compiled = false
    var nextIsString = false
    var overflowProtection = false
    var optimise = false
    var strip = false
    var export = false
    var time = false

    fun makeCompileOptions(): SystemRunnerOptions? {
        return if (compiled) {
            SystemRunnerOptions(
                export = export,
                overflowProtection = overflowProtection,
            )
        } else {
            null
        }
    }

    for (arg in args) {
        if (nextIsString) {
            nextIsString = false
            runProgram(arg, optimise, strip, time, makeCompileOptions())
            continue
        }

        if (arg in arrayOf("--optimise", "-O")) {
            optimise = true
            continue
        }

        if (arg in arrayOf("--overflow-protection", "-o")) {
            overflowProtection = true
            continue
        }

        if (arg in arrayOf("--strip", "-S")) {
            strip = true
            continue
        }

        if (arg in arrayOf("--compile", "-c")) {
            compiled = true
            continue
        }
        if (arg == "--interpreted") {
            compiled = false
            continue
        }

        if (arg in arrayOf("--export", "-E")) {
            export = true
            continue
        }

        if (arg == "--no-export") {
            export = false
            continue
        }

        if (arg in arrayOf("--time", "-t")) {
            time = true
            continue
        }

        if (arg in arrayOf("--eval", "-e")) {
            nextIsString = true
            continue
        }

        if (arg.startsWith("-")) {
            for (i in 1 until arg.length) {
                when (arg[i]) {
                    'O' -> optimise = true
                    'S' -> strip = true
                    'c' -> compiled = true
                    'i' -> compiled = false
                    'E' -> export = true
                    'e' -> nextIsString = true
                    'o' -> overflowProtection = true
                    't' -> time = true
                    else -> {
                        println("Unknown argument: ${arg[i]}")
                        return
                    }
                }
            }

            continue
        }

        if (arg.endsWith(".b") || arg.endsWith(".bf")) {
            val program = Path(arg).readText()
            runProgram(program, optimise, strip, time, makeCompileOptions())
        } else {
            println("Unknown argument: $arg")
            return
        }
    }
}

private fun runProgram(
    literal: String,
    optimise: Boolean,
    strip: Boolean,
    printTime: Boolean,
    systemRunnerOpts: SystemRunnerOptions?
) {
    var program = bfParse(literal)
    if (optimise)
        program = bfOptimise(program)
    if (strip)
        program = bfStrip(program)

    val runner = if (systemRunnerOpts != null) {
        systemRunner(systemRunnerOpts)
    } else {
        InterpreterRunner
    }
    val executable = runner.compile(program)

    val time = measureTime { executable.run(SysInInput, SysOutOutput) }

    if (printTime) {
        System.err.println("Execution time: ${formatTime(time)}")
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
