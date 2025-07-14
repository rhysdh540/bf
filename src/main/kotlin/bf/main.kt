package bf

import bf.opt.bfOptimise
import bf.opt.bfStrip
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.measureTime

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
            |       --string, -s
            |               Run the next argument as a string
            |       --export, -e
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

    for (arg in args) {
        if (nextIsString) {
            nextIsString = false
            val program = arg
            runProgram(program, compiled, optimise, strip, time, CompileOptions(
                localVariables = export,
                export = export,
                overflowProtection = overflowProtection,
            ))
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

        if (arg in arrayOf("--export", "-e")) {
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

        if (arg in arrayOf("--string", "-s")) {
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
                    'e' -> export = true
                    's' -> nextIsString = true
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
            runProgram(program, compiled, optimise, strip, time, CompileOptions(
                localVariables = export,
                export = export,
                overflowProtection = overflowProtection,
            ))
        } else {
            println("Unknown argument: $arg")
            return
        }
    }
}

private fun runProgram(
    literal: String,
    compile: Boolean,
    optimise: Boolean,
    strip: Boolean,
    printTime: Boolean,
    opts: CompileOptions
) {
    val time = measureTime {
        var program = bfParse(literal)
        if (optimise)
            program = bfOptimise(program)
        if (strip)
            program = bfStrip(program)

        if (compile) {
            val compiled = bfCompile(program, opts)

            compiled(SysOutWriter, System.`in`.reader())
        } else {
            bfRun(program)
        }
    }

    if (printTime) {
        println("Execution time: ${time.inWholeMilliseconds}ms")
    }
}
