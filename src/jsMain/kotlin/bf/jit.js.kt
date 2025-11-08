package bf

import kotlin.math.absoluteValue
import kotlin.random.Random

actual fun bfCompile(program: Iterable<BFOperation>, opts: CompileOptions): (OutputConsumer, InputProvider) -> Unit {
    val tapeSizeIsPowerOf2 = opts.tapeSize and (opts.tapeSize - 1) == 0
    val funcName = "bf_${Random.nextInt().absoluteValue.toString(16)}"

    fun genOps(ops: Iterable<BFOperation>, sb: StringBuilder, indent: String) {
        for (op in ops) {
            when (op) {
                is PointerMove -> {
                    sb.append("$indent ptr += ${op.value};\n")
                }
                is ValueChange -> {
                    val off = op.offset
                    val v = op.value
                    sb.append("$indent {\n")
                    sb.append("$indent  var i = idx($off);\n")
                    sb.append("$indent  var nv = ((tape[i] & 0xFF) + ${v}) & 0xFF;\n")
                    sb.append("$indent  if (nv > 127) nv -= 256;\n")
                    sb.append("$indent  tape[i] = nv;\n")
                    sb.append("$indent}\n")
                }
                is Print -> {
                    val off = op.offset
                    sb.append("$indent out((tape[idx($off)] & 0xFF));\n")
                }
                is Input -> {
                    val off = op.offset
                    sb.append("$indent {\n")
                    sb.append("$indent  var r = inp();\n")
                    sb.append("$indent  var v = r & 0xFF;\n")
                    sb.append("$indent  if (v > 127) v -= 256;\n")
                    sb.append("$indent  tape[idx($off)] = v;\n")
                    sb.append("$indent}\n")
                }
                is Loop -> {
                    sb.append("$indent while ((tape[idx(0)] & 0xFF) !== 0) {\n")
                    genOps(op, sb, "$indent  ")
                    sb.append("$indent }\n")
                }
                is SetToConstant -> {
                    val off = op.offset
                    val v = op.value.toInt()
                    sb.append("$indent tape[idx($off)] = ${if (v > 127) v - 256 else v};\n")
                }
                is Copy -> {
                    sb.append("$indent {\n")
                    sb.append("$indent  var cur = (tape[idx(0)] & 0xFF);\n")
                    sb.append("$indent  tape[idx(0)] = 0;\n")
                    for ((offset, multiplier) in op.multipliers) {
                        sb.append("$indent  {\n")
                        sb.append("$indent    var i = idx($offset);\n")
                        if (multiplier.absoluteValue != 1) {
                            sb.append("$indent    var add = (cur * ${multiplier.absoluteValue}) & 0xFF;\n")
                        } else {
                            sb.append("$indent    var add = cur & 0xFF;\n")
                        }
                        if (multiplier < 0) {
                            sb.append("$indent    var nv = ((tape[i] & 0xFF) - add) & 0xFF;\n")
                        } else {
                            sb.append("$indent    var nv = ((tape[i] & 0xFF) + add) & 0xFF;\n")
                        }
                        sb.append("$indent    if (nv > 127) nv -= 256;\n")
                        sb.append("$indent    tape[i] = nv;\n")
                        sb.append("$indent  }\n")
                    }
                    sb.append("$indent}\n")
                }
            }
        }
    }

    val sb = StringBuilder()
    sb.append("function $funcName(out, inp) {\n")
    sb.append("  var tape = new Int8Array(${opts.tapeSize});\n")
    sb.append("  var ptr = ${opts.tapeSize / 2};\n")

    if (opts.overflowProtection) {
        if (tapeSizeIsPowerOf2) {
            val mask = opts.tapeSize - 1
            sb.append("  function idx(off) { return (ptr + off) & $mask; }\n")
        } else {
            sb.append("  function idx(off) { var i = ptr + off; i = i % tape.length; if (i < 0) i += tape.length; return i; }\n")
        }
    } else {
        sb.append("  function idx(off) { return ptr + off; }\n")
    }

    genOps(program, sb, "  ")

    sb.append("}\n")

    if (opts.export) {
        println(sb.toString())
    }

    val jsFunc = eval("($sb)")

    return { out, inp ->
        val write: (Int) -> Unit = { v -> out.write(v) }
        val read: () -> Int = { inp.read() }
        jsFunc(write, read)
    }
}