package dev.rdh.bf

context(s: StringBuilder)
operator fun CharSequence.unaryPlus() = s.appendLine(this)

interface SysCallProvider {
    val write: ULong
    val read: ULong
    val exit: ULong
}

expect val sys: SysCallProvider

actual val nativeRunner: BfRunner = NasmWriter