package dev.rdh.bf.util

import dev.rdh.bf.BfOutput
import dev.rdh.bf.FileSystem
import dev.rdh.bf.Terminal
import dev.rdh.bf.write

class LineEditor(
    private val terminal: Terminal,
    private val output: BfOutput,
    private val fs: FileSystem,
    private val completions: () -> List<String> = { emptyList() },
) {
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var savedLine = ""

    fun readLine(prompt: String): String? {
        output.write(prompt)
        output.flush()

        val buf = StringBuilder()
        var cursor = 0
        historyIndex = -1
        savedLine = ""

        terminal.enableRawMode()
        try {
            while (true) {
                val c = terminal.readRawByte()
                if (c < 0) {
                    terminal.disableRawMode()
                    return null
                }

                when (c) {
                    '\n'.code, '\r'.code -> {
                        output.write("\r\n")
                        output.flush()
                        val line = buf.toString()
                        if (line.isNotBlank() && history.lastOrNull() != line) {
                            history.add(line)
                        }
                        terminal.disableRawMode()
                        return line
                    }

                    3 -> {
                        output.write("^C\r\n")
                        output.flush()
                        terminal.disableRawMode()
                        return null
                    }

                    127 -> {
                        if (cursor > 0) {
                            buf.deleteAt(cursor - 1)
                            cursor--
                            redraw(prompt, buf, cursor)
                        }
                    }

                    '\t'.code -> {
                        val result = complete(buf.toString(), cursor, prompt, buf)
                        if (result != null) {
                            buf.clear()
                            buf.append(result.first)
                            cursor = result.second
                            redraw(prompt, buf, cursor)
                        }
                    }

                    27 -> {
                        val next = terminal.readRawByte()
                        if (next == '['.code) {
                            when (terminal.readRawByte()) {
                                'A'.code -> { // up
                                    if (history.isNotEmpty() && historyIndex < history.size - 1) {
                                        if (historyIndex == -1) savedLine = buf.toString()
                                        historyIndex++
                                        val entry = history[history.size - 1 - historyIndex]
                                        buf.clear()
                                        buf.append(entry)
                                        cursor = buf.length
                                        redraw(prompt, buf, cursor)
                                    }
                                }
                                'B'.code -> { // down
                                    if (historyIndex > -1) {
                                        historyIndex--
                                        val entry = if (historyIndex == -1) savedLine
                                        else history[history.size - 1 - historyIndex]
                                        buf.clear()
                                        buf.append(entry)
                                        cursor = buf.length
                                        redraw(prompt, buf, cursor)
                                    }
                                }
                                'C'.code -> { // right
                                    if (cursor < buf.length) {
                                        cursor++
                                        output.write("\u001b[C")
                                        output.flush()
                                    }
                                }
                                'D'.code -> { // left
                                    if (cursor > 0) {
                                        cursor--
                                        output.write("\u001b[D")
                                        output.flush()
                                    }
                                }
                                '3'.code -> { // Delete key sends ESC [ 3 ~
                                    val tilde = terminal.readRawByte()
                                    if (tilde == '~'.code && cursor < buf.length) {
                                        buf.deleteAt(cursor)
                                        redraw(prompt, buf, cursor)
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        if (c in 32..126) {
                            buf.insert(cursor, c.toChar())
                            cursor++
                            if (cursor == buf.length) {
                                output.writeByte(c)
                                output.flush()
                            } else {
                                redraw(prompt, buf, cursor)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            terminal.disableRawMode()
            return null
        }
    }

    private fun redraw(prompt: String, buf: StringBuilder, cursor: Int) {
        output.write("\r\u001b[2K")
        output.write(prompt)
        output.write(buf)
        val back = buf.length - cursor
        if (back > 0) {
            output.write("\u001b[${back}D")
        }
        output.flush()
    }

    private fun complete(line: String, cursor: Int, prompt: String, buf: StringBuilder): Pair<String, Int>? {
        val before = line.substring(0, cursor)
        val lastSpace = before.lastIndexOf(' ')
        val token = before.substring(lastSpace + 1)
        val after = line.substring(cursor)

        if (token.isEmpty()) return null

        val lastSlash = token.lastIndexOf('/')
        val dir: String
        val partial: String
        if (lastSlash >= 0) {
            dir = token.substring(0, lastSlash + 1)
            partial = token.substring(lastSlash + 1)
        } else {
            dir = ""
            partial = token
        }

        val dirPath = dir.ifEmpty { "." }
        val fileCandidates = fs.listFiles(dirPath)
            .filter { it.startsWith(partial) }
            .map { name ->
                val fullPath = dir + name
                if (fs.isDirectory(if (dir.isEmpty()) name else fullPath)) "$fullPath/" else fullPath
            }

        val optionCandidates = if (dir.isEmpty()) {
            completions().filter { it.startsWith(partial) }
        } else {
            emptyList()
        }

        val candidates = (optionCandidates + fileCandidates).distinct()

        return when {
            candidates.isEmpty() -> null
            candidates.size == 1 -> {
                val completed = candidates[0]
                val suffix = if (completed.endsWith("/")) "" else " "
                val newBefore = before.substring(0, lastSpace + 1) + completed + suffix
                Pair(newBefore + after, newBefore.length)
            }
            else -> {
                val common = candidates.reduce { a, b ->
                    a.commonPrefixWith(b)
                }
                if (common.length > token.length) {
                    val newBefore = before.substring(0, lastSpace + 1) + common
                    Pair(newBefore + after, newBefore.length)
                } else {
                    output.write("\r\n")
                    for (c in candidates) {
                        output.write("$c  ")
                    }
                    output.write("\r\n")
                    redraw(prompt, buf, cursor)
                    null
                }
            }
        }
    }
}
