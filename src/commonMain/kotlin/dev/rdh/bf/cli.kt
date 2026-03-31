package dev.rdh.bf

import dev.rdh.bf.util.LineEditor
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

private const val DEFAULT_TAPE_SIZE = 1 shl 15

abstract class CommandLine {
    protected abstract val stdin: BfInput
    protected abstract val stdout: BfOutput
    protected abstract val stderr: BfOutput
    protected abstract fun exit(code: Int): Nothing

    protected abstract val nativeCodeType: String
    protected abstract val systemRunner: BfRunner?
    protected abstract val terminal: Terminal
    protected abstract val fs: FileSystem

    private var compiled = false
    private var printTime = false
    private var nextIsString = false
    private var tapeSize = DEFAULT_TAPE_SIZE
    protected var autoExec = false

    protected val options: List<Option> by lazy {
        listOfNotNull(
            Option("help", 'h', "Show this help message") {
                stderr.write("Usage: bf [options] <program.b>\n")
                stderr.write("run with no arguments to enter interactive mode\n")
                stderr.write("Options:\n")
                for (option in options) {
                    stderr.write("       --${option.name}")
                    if (option.short != null) {
                        stderr.write(", -${option.short}")
                    }
                    stderr.write("\n               ${option.description}\n")
                }
            },
            Option("compile", 'c', "if true, compile the following programs to $nativeCodeType; else run in interpreted mode (default)") {
                compiled = it?.let { truthy(it) } ?: true
            }.takeIf { systemRunner != null },
            Option("tape-size", 's', "Set the tape size for the following programs (default: $DEFAULT_TAPE_SIZE)") {
                tapeSize = it?.toIntOrNull() ?: run {
                    stderr.write("Invalid tape size: $it\n")
                    exit(1)
                }
            },
            Option("time", 't', "Print the time taken to execute the following programs") {
                printTime = it?.let { truthy(it) } ?: true
            },
            Option("eval", 'e', "Evaluate the next argument directly as a Brainfuck program") {
                if (it != null) {
                    stderr.write("Warning: --eval does not take an argument, ignoring '$it'\n")
                }
                nextIsString = true
            }
        )
    }

    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            repl()
        }
        exit(processArgs(args))
    }

    protected open fun repl(): Nothing {
        autoExec = true
        val editor = LineEditor(terminal, stderr, fs) {
            options.map { "--${it.name}" }
        }

        while (true) {
            val line = editor.readLine("> ") ?: break
            if (line.isBlank()) continue
            try {
                processArgs(line.trim().split("\\s+".toRegex()).toTypedArray())
            } catch (e: Exception) {
                stderr.write("${e::class.simpleName}: ${e.message}\n")
            }
        }
        exit(0)
    }

    protected fun processArgs(args: Array<String>): Int {
        for (arg in args) {
            if (nextIsString) {
                nextIsString = false
                exec(arg)
                continue
            }

            if (options.any { it.evaluate(arg) }) {
                continue
            }

            if (arg.getOrNull(0) == '-' && arg.length > 2) {
                var found = false
                for (inner in arg.drop(1)) {
                    if (options.any { it.evaluate("-$inner") }) {
                        found = true
                    }
                }

                if (found) continue
            }

            val content = fs.readFile(arg).getOrNull()
            if (content != null) {
                exec(content)
            } else if (autoExec) {
                exec(arg)
            } else {
                stderr.write("Error reading file '$arg'\n")
                return 1
            }
        }

        return 0
    }

    protected fun exec(literal: String) {
        val (program, ptime) = measureTimedValue { Parser.parse(literal) }

        val runner = if (compiled) systemRunner!! else Interpreter

        val (executable, cTime) = measureTimedValue { runner.compile(program, tapeSize) }

        val time = measureTime { executable.run(stdin, stdout) }
        stdout.flush()

        if (printTime) {
            stderr.write("\nparse / compile / execute:\n")
            stderr.write("${formatTime(ptime)} / ${formatTime(cTime)} / ${formatTime(time)}\n")
            stderr.flush()
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

    private fun truthy(s: String): Boolean {
        return s.lowercase() in setOf("true", "1", "yes", "y", "on")
    }
}

data class Option(
    val name: String,
    val short: Char?,
    val description: String,
    val action: (String?) -> Unit
) {
    fun evaluate(arg: String): Boolean {
        val parts = arg.split('=', limit = 2)
        if (parts[0] == "--$name" || (short != null && parts[0] == "-$short")) {
            action(parts.getOrNull(1))
            return true
        }
        return false
    }
}

interface FileSystem {
    fun readFile(path: String): Result<String>
    fun listFiles(dir: String): List<String>
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
}

interface Terminal {
    fun enableRawMode()
    fun disableRawMode()
    fun readRawByte(): Int
}