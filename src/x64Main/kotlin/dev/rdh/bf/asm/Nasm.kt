package dev.rdh.bf.asm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKString

class Nasm : Assembler {
    private val s = StringBuilder()

    init {
        s
            .appendLine("bits 64")
            .appendLine("default rel")
            .appendLine("section .text")
            .appendLine("global _start")
    }

    private operator fun String.unaryPlus() {
        s.append("    ").appendLine(this)
    }

    private fun reqSame(dest: DataDestination, src: DataSource) {
        require(dest !is Memory || src !is Memory) { "cannot mov memory to memory" }
        val destWidth = when (dest) {
            is Register -> dest.width
            is Memory -> dest.width
            else -> throw IllegalArgumentException()
        }
        val srcWidth = when (src) {
            is Register -> src.width
            is Memory -> src.width
            else -> destWidth
        }
        require(srcWidth == destWidth) { "source $src is different width than dest $dest" }
    }

    override fun mov(dest: DataDestination, src: DataSource) {
        reqSame(dest, src)
        +"mov ${dest.asString()}, ${src.asString()}"
    }

    override fun movzx(dest: Register, src: Memory) {
        require(dest.width > src.width) { "movzx dest $dest must be wider than source $src" }
        require(src.width <= 16u) { "movzx source must be byte or word" }
        +"movzx ${dest.asString()}, ${src.asString()}"
    }

    override fun lea(dest: Register, src: Memory) {
        +"lea ${dest.asString()}, ${src.asString()}"
    }

    override fun add(dest: DataDestination, src: DataSource) {
        reqSame(dest, src)
        +"add ${dest.asString()}, ${src.asString()}"
    }

    override fun sub(dest: DataDestination, src: DataSource) {
        reqSame(dest, src)
        +"sub ${dest.asString()}, ${src.asString()}"
    }

    override fun imul(dest: Register, src: DataSource, imm: Immediate?) {
        reqSame(dest, src)
        if (imm == null) {
                require(src !is Immediate) { "cannot imul with immediate source if no immediate argument is given" }
        }
        +"imul ${dest.asString()}, ${src.asString()}".let {
            if (imm != null) "$it, ${imm.asString()}" else it
        }
    }

    override fun inc(dest: DataDestination) {
        +"inc ${dest.asString()}"
    }

    override fun dec(dest: DataDestination) {
        +"dec ${dest.asString()}"
    }

    override fun syscall() = +"syscall"
    override fun ret() = +"ret"

    override fun call(label: String) = +"call $label"
    override fun cmp(op1: DataDestination, op2: DataSource) {
        reqSame(op1, op2)
        +"cmp ${op1.asString()}, ${op2.asString()}"
    }
    override fun jmp(label: String) = +"jmp $label"
    override fun jne(label: String) = +"jne $label"
    override fun mark(label: String) {
        s.appendLine("$label:")
    }

    fun addRaw(line: String) {
        s.appendLine(line)
    }

    fun build(): String = s.toString()

    companion object {
        operator fun invoke(block: Nasm.() -> Unit): String {
            val nasm = Nasm()
            nasm.block()
            return nasm.build()
        }

        @OptIn(ExperimentalForeignApi::class)
        fun compile(source: String): Pair<String, String> {
            val outfile = platform.posix.tmpnam(null)?.toKString() ?: error("Failed to create temp file")
            val ofile = "$outfile.o"
            val srcfile = "$outfile.nasm"
            val fd = platform.posix.open(srcfile, platform.posix.O_RDWR or platform.posix.O_CREAT, 0b110_110_110) // rw-rw-rw-
            if (fd == -1) error("Failed to open temp file")
            platform.posix.write(fd, source.cstr, source.length.toULong())
            platform.posix.close(fd)

            platform.posix.system("nasm -fmacho64 $srcfile -o $ofile").let {
                if (it != 0) error("Failed to assemble: $it")
            }
            platform.posix.system("ld -o $outfile $ofile -lSystem -syslibroot /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk -e _start -arch x86_64 -platform_version macos 15.0.0 26.2").let {
                if (it != 0) error("Failed to link: $it")
            }

            return outfile to srcfile
        }
    }
}