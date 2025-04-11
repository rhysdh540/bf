package dev.rdh.bf.asm

interface Register : DataSource, DataDestination {
    val code: UInt
    val width: UInt
}

enum class GP64 : Register {
    RAX, RCX, RDX, RBX, RSP, RBP, RSI, RDI,
    R8, R9, R10, R11, R12, R13, R14, R15;

    override val width = 64u
    override val code = ordinal.toUInt()
    override fun asString() = name.lowercase()
}

enum class GP32 : Register {
    EAX, ECX, EDX, EBX, ESP, EBP, ESI, EDI,
    R8D, R9D, R10D, R11D, R12D, R13D, R14D, R15D;

    override val width = 32u
    override val code = ordinal.toUInt()
    override fun asString() = name.lowercase()
}

enum class GP16 : Register {
    AX, CX, DX, BX, SP, BP, SI, DI,
    R8W, R9W, R10W, R11W, R12W, R13W, R14W, R15W;

    override val width = 16u
    override val code = ordinal.toUInt()
    override fun asString() = name.lowercase()
}

enum class GP8 : Register {
    AL, CL, DL, BL, SPL, BPL, SIL, DIL,
    R8B, R9B, R10B, R11B, R12B, R13B, R14B, R15B;

    override val width = 8u
    override val code = ordinal.toUInt()
    override fun asString() = name.lowercase()
}

// R8 and above require REX prefix
val Register.rex: Boolean get() = code > 7u

// lower 3 bits of code are used for ModR/M encoding
val Register.rm: UInt get() = code and 7u

val Register.as64: GP64 get() = GP64.entries[code.toInt()]
val Register.as32: GP32 get() = GP32.entries[code.toInt()]
val Register.as16: GP16 get() = GP16.entries[code.toInt()]
val Register.as8: GP8 get() = GP8.entries[code.toInt()]