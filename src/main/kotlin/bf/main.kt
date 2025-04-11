package bf

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

    bfRun(program)
}