package bf

import java.io.Reader
import java.io.Writer
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.measureTime

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: bf <program.bf>")
        return
    };
    val programText = if (args[0].all { it in "+-,.<>[]" }) {
        args[0]
    } else {
        Path(args[0]).readText()
    }

    var program = bfParse(programText)
    program = bfStrip(bfOptimise(program))
    //println(bfStringify(program))

//    timeInterpreted(program)
//    timeCompiled(program)
//    bfRun(program)
    defaultCompiledRun(bfCompile(program))

    bfCompile(program, CompileOptions(
        export = true,
        localVariables = true,
        packag = "",
        selfContained = true,
        mainMethod = true,
    ))
}

private fun defaultCompiledRun(program: (Writer, Reader) -> Unit) {
    val w = System.out.writer()
    val r = System.`in`.reader()
    program(w, r)
    w.flush()
}

private fun timeInterpreted(program: Iterable<BFOperation>) {
    val w = System.out.writer()
    val r = Reader.nullReader()
    val time = measureTime {
        repeat(1000) {
            bfRun(program, w, r)
        }
    }

    println("ran (interpreted) in ${time.inWholeNanoseconds / 1000}ns")
}

private fun timeCompiled(program: Iterable<BFOperation>) {
    val comp = bfCompile(program)
    val w = Writer.nullWriter()
    val r = Reader.nullReader()
    val time = measureTime {
        repeat(1000) {
            comp(w, r)
        }
    }
    println("ran (compiled) in ${time.inWholeNanoseconds / 1000}ns")
}
