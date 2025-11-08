@file:JvmName("Main")
package bf

import bf.opt.bfOptimise
import bf.opt.bfStrip
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.measureTime

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("""
            |bf: the Brainfuck toolkit
            |Usage: bf.jar <command> [options]
            |Commands:
            |       run <program.b>
            |               Run the given Brainfuck program
            |       generate <text>
            |               Generate a Brainfuck program that outputs the given text
        """.trimMargin())
        return
    }

    val subargs = args.sliceArray(1 until args.size)
    when (args[0]) {
        "run" -> run(subargs)
        "generate" -> generate(subargs)
        else -> {
            println("Unknown command: ${args[0]}")
        }
    }
}

fun generate(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println("""
            |Usage: bf.jar generate <text>
            |Options:
            |       --help, -h
            |               Show this help message
        """.trimMargin())
        return
    }
}

fun run(args: Array<String>) {
    if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
        println("""
            |Usage: bf.jar run <program.b>
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
            |               Run the next argument directly as a Brainfuck program
            |       --export, -E
            |               Export the following programs to a file in `.bf.out`
            |       --time, -t
            |               Time the following programs
            |       --no-export
            |               Do not export the following programs
        """.trimMargin())
        return
    }

    var compiled = false
    var nextIsString = false
    var overflowProtection = false
    var optimise = false
    var strip = false
    var export = false
    var time = false

    fun makeCompileOptions(): CompileOptions? {
        return if (compiled) {
            CompileOptions(
                localVariables = export,
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
    opts: CompileOptions?
) {
    var program = bfParse(literal)
    if (optimise)
        program = bfOptimise(program)
    if (strip)
        program = bfStrip(program)

    val time = measureTime(if (opts != null) {
        val compiled = bfCompile(program, opts);
        { compiled(SysOutWriter, SysInReader) }
    } else {
        { bfRun(program, SysOutWriter, SysInReader) }
    })

    if (printTime) {
        println("Execution time: ${formatTime(time)}")
    }
}

private fun formatTime(time: Duration): String {
    val seconds = time.inWholeSeconds
    val milliseconds = time.inWholeMilliseconds % 1000
    return if (seconds > 0) {
        "${seconds}.${milliseconds}s"
    } else {
        "${milliseconds}ms"
    }
}
