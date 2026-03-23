package dev.rdh.bf.asm

interface DataSource

interface DataDestination

value class Immediate(val value: Long) : DataSource {
    constructor(value: Int) : this(value.toLong())
    constructor(value: Short) : this(value.toLong())
    constructor(value: Byte) : this(value.toLong())

    constructor(value: ULong) : this(value.toLong())
    constructor(value: UInt) : this(value.toLong())
    constructor(value: UShort) : this(value.toLong())
    constructor(value: UByte) : this(value.toLong())
}