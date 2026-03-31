package dev.rdh.bf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.ECHO
import platform.posix.F_OK
import platform.posix.ICANON
import platform.posix.ISIG
import platform.posix.STDIN_FILENO
import platform.posix.TCSANOW
import platform.posix.VMIN
import platform.posix.VTIME
import platform.posix.cfmakeraw
import platform.posix.fileno
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios

@OptIn(ExperimentalForeignApi::class)
object Main : CommandLine() {
    override val stdin = platform.posix.stdin?.bfInput() ?: throw IllegalStateException("Standard input is not available")
    override val stdout = platform.posix.stdout?.bfOutput() ?: throw IllegalStateException("Standard output is not available")
    override val stderr = platform.posix.stderr?.bfOutput() ?: throw IllegalStateException("Standard error is not available")
    override fun exit(code: Int): Nothing {
        platform.posix.exit(code)
        error("Failed to exit with code $code")
    }

    override val terminal = object : Terminal {
        var original: termios? = null
        var new: termios? = null

        override fun enableRawMode() {
            if (original != null) return
            val original = nativeHeap.alloc<termios>()
            if (tcgetattr(STDIN_FILENO, original.ptr) != 0) {
                throw IllegalStateException("Failed to get terminal attributes")
            }

            val new = nativeHeap.alloc<termios>()
            if (tcgetattr(STDIN_FILENO, new.ptr) != 0) {
                throw IllegalStateException("Failed to get terminal attributes")
            }

            cfmakeraw(new.ptr)
            if (tcsetattr(STDIN_FILENO, 0, new.ptr) != 0) {
                throw IllegalStateException("Failed to set terminal attributes")
            }

            this.original = original
            this.new = new
        }

        override fun disableRawMode() {
            val original = original ?: return
            if (tcsetattr(STDIN_FILENO, TCSANOW, original.ptr) != 0) {
                throw IllegalStateException("Failed to get terminal attributes")
            }

            nativeHeap.free(original.rawPtr)
            this.original = null
            nativeHeap.free(new!!.rawPtr)
            this.new = null
        }

        override fun readRawByte(): Int = platform.posix.getchar()
    }

    override val fs = object : FileSystem {
        override fun readFile(path: String): Result<String> {
            return runCatching {
                val fd = platform.posix.open(path, platform.posix.O_RDONLY)
                if (fd == -1) {
                    throw IllegalStateException("Failed to open file: $path")
                }
                try {
                    val buffer = ByteArray(1024)
                    val sb = StringBuilder()
                    while (true) {
                        val bytesRead = platform.posix.read(fd, buffer.refTo(0), buffer.size.toULong()).toInt()
                        if (bytesRead <= 0) break
                        sb.append(buffer.decodeToString(0, bytesRead))
                    }
                    sb.toString()
                } finally {
                    platform.posix.close(fd)
                }
            }
        }

        override fun listFiles(dir: String): List<String> {
            val dirp = platform.posix.opendir(dir) ?: return emptyList()
            val result = mutableListOf<String>()
            try {
                while (true) {
                    val entry = platform.posix.readdir(dirp) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == "..") continue
                    result.add(name)
                }
            } finally {
                platform.posix.closedir(dirp)
            }
            return result.sorted()
        }

        override fun exists(path: String): Boolean {
            return platform.posix.access(path, F_OK) == 0
        }

        override fun isDirectory(path: String): Boolean = memScoped {
            val statbuf = alloc<platform.posix.stat>()
            if (platform.posix.stat(path, statbuf.ptr) != 0) {
                return false
            }
            return (statbuf.st_mode.toInt() and platform.posix.S_IFMT) == platform.posix.S_IFDIR
        }
    }

    override val nativeCodeType = "native code"
    override val systemRunner = nativeRunner
}

fun main(args: Array<String>) = Main.run(args)