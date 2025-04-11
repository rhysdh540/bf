package bf

import java.io.Reader
import kotlin.io.path.Path
import kotlin.io.path.readText

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

//    bfRun(program)
    System.setProperty("bf.debug.lvs", "true")
    System.setProperty("bf.debug.export", "true")
    Path(".bf.out").toFile().deleteRecursively()

    val compiled = bfCompile(program)
    System.out.writer().use {
        compiled(it, Reader.nullReader())
    }
}