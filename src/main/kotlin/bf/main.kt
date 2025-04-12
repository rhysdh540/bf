package bf

import kotlin.io.path.Path
import kotlin.io.path.readText

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("""
            |Usage: bf.jar <program.b>
            |Options:
            |       --optimise, -O  Optimise the intermediate representation of the program
            |       --strip, -S     Strip unused code from the program
            |       --compile       Compile the program to a function
            |       --interpreted   Run the program in interpreted mode (default)
            |       --string, -s    Run the next argument as a string
            |       <program.b(f)>    Run the program in the file
        """.trimMargin())
        return
    }

    var compiled = false
    var nextIsString = false
    var optimise = false
    var strip = false

    for (arg in args) {
        if (nextIsString) {
            nextIsString = false
            val program = arg
            runProgram(program, compiled, optimise, strip)
            continue
        }

        if (arg == "--optimise" || arg == "-O") {
            optimise = true
            continue
        }

        if (arg == "--strip" || arg == "-S") {
            strip = true
            continue
        }

        if (arg == "--compile") {
            compiled = true
            continue
        }
        if (arg == "--interpreted") {
            compiled = false
            continue
        }

        if (arg == "--string" || arg == "-s") {
            nextIsString = true
            continue
        }

        if (arg.startsWith("-")) {
            println("Unknown option: $arg")
            return
        }

        if (arg.endsWith(".b") || arg.endsWith(".bf")) {
            val program = Path(arg).readText()
            runProgram(program, compiled, optimise, strip)
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
) {
    var program = bfParse(literal)
    if (optimise)
        program = bfOptimise(program)
    if (strip)
        program = bfStrip(program)

    if (compile) {
        val compiled = bfCompile(program)
        val w = System.out.writer()
        compiled(w, System.`in`.reader())
        w.flush()
    } else {
        bfRun(program)
    }
}
