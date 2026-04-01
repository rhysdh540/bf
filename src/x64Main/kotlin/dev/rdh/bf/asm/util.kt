package dev.rdh.bf.asm

// width returned is in bits
fun DataDestination.widthOr(def: UInt?) = when (this) {
    is Register -> width
    is Memory -> width
    else -> def ?: error("cannot determine width of $this")
}

fun DataSource.widthOr(def: UInt?) = when (this) {
    is Register -> width
    is Memory -> width
    else -> def ?: error("cannot determine width of $this")
}

fun reqSame(dest: DataDestination, src: DataSource) {
    require(dest !is Memory || src !is Memory) { "cannot operate memory to memory" }
    val destWidth = dest.widthOr(null)
    val srcWidth = src.widthOr(destWidth)
    require(srcWidth == destWidth) { "source $src is different width than dest $dest" }
}