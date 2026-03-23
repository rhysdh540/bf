package dev.rdh.bf

actual val sys = object : SysCallProvider {
    override val write = 0x2000004UL
    override val read = 0x2000003UL
    override val exit = 0x2000001UL
}