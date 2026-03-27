package dev.rdh.bf

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.CodeSizeEvaluator
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.util.CheckClassAdapter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.PrintWriter
import java.io.Reader
import java.io.Writer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.abs
import kotlin.random.Random

object Compiler : BfRunner {
    override fun compile(program: Iterable<BfBlockOp>, tapeSize: Int): BfExecutable {
        cache[program.toList()]?.let { return it }
        val className = "BFProgram$${Random.nextInt().toHexString()}"
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_5, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

        cw.method(ACC_PUBLIC or ACC_STATIC, "main", desc<Void>(type<Array<String>>())) {
            // new InputStreamReader(System.in)
            new<InputStreamReader>()
            dup
            getstatic<System, InputStream>("in")
            invokespecial<InputStreamReader>("<init>", desc<Void>(type<InputStream>()))

            // new OutputStreamWriter(System.out)
            new<OutputStreamWriter>()
            dup
            getstatic<System, PrintStream>("out")
            invokespecial<OutputStreamWriter>("<init>", desc<Void>(type<OutputStream>()))
            dup_x1
            invokestatic(className, "run", desc<Void>(type<Reader>(), type<Writer>()))
            invokevirtual<OutputStreamWriter>("flush", desc<Void>())
            areturn<Void>()

            parameter("args")
        }

        val mw = cw.method(name = "run", descriptor = desc<Void>(type<Reader>(), type<Writer>()))
        mw.visitCode()

        val input = mw.local<Reader>(0)
        val output = mw.local<Writer>(1)

        val tape = mw.local<ByteArray>(2)
        mw.int(tapeSize)
        mw.newarray<Byte>()
        mw.store(tape)

        // initialize pointer: int
        val pointer = mw.local<Int>(3)
        mw.int(tapeSize / 2)
        mw.store(pointer)

        fun MethodVisitor.addOffset(offset: Int) {
            if (offset == 0) return
            int(abs(offset))
            if (offset >= 0) iadd else isub
        }

        val scratchBase = 4

        // bf code has a lot of repeated loops, so we can reuse the same method
        val loopCache = mutableMapOf<Loop, String>()
        var loopI = 1
        val loopMethodDescriptor = desc<Int>(type<Reader>(), type<Writer>(), type<ByteArray>(), type<Int>())

        // loop bodies go in separate functions, because the jvm can't handle large methods well
        fun makeLoopBody(loop: Loop, writeOp: MethodVisitor.(BfBlockOp) -> Unit): String {
            return loopCache.getOrPut(loop) {
                val methodName = "loop$loopI"
                loopI++
                cw.method(ACC_PRIVATE or ACC_STATIC, methodName, loopMethodDescriptor) {
                    for (op in loop.body) {
                        writeOp(op)
                    }

                    load(pointer)
                    areturn<Int>()

                    parameters("in", "out", "tape", "pointer")
                }
                methodName
            }
        }

        fun MethodVisitor.shiftPointer(delta: Int) {
            when (delta) {
                0 -> Unit
                in Short.MIN_VALUE..Short.MAX_VALUE -> inc(pointer, delta)
                else -> {
                    load(pointer)
                    addOffset(delta)
                    store(pointer)
                }
            }
        }

        fun MethodVisitor.loadCell(offset: Int) {
            load(tape)
            load(pointer)
            addOffset(offset)
            baload
        }

        fun MethodVisitor.writeExpr(expr: AffineExpr, localByRef: Map<Int, LocalVar>) {
            if (expr.constant != 0 || expr.terms.isEmpty()) {
                int(expr.constant)
            }

            val liveTerms = expr.terms.filter { it.coeff != 0 }
            for ((i, term) in liveTerms.withIndex()) {

                // Build the product: |coeff| * cell[off0] * cell[off1] * ...
                if (abs(term.coeff) != 1) {
                    int(abs(term.coeff))
                    for (off in term.offsets) {
                        val local = localByRef[off]
                        if (local != null) load(local) else loadCell(off)
                        imul
                    }
                } else {
                    // |coeff| == 1: start with first cell, multiply rest
                    for ((j, off) in term.offsets.withIndex()) {
                        val local = localByRef[off]
                        if (local != null) load(local) else loadCell(off)
                        if (j > 0) imul
                    }
                }

                // Accumulate into running sum
                if (i != 0 || expr.constant != 0) {
                    if (term.coeff > 0) iadd else isub
                } else {
                    if (term.coeff < 0) ineg
                }
            }
        }

        fun MethodVisitor.writeBlock(block: BfBlockOp): Unit = when (block) {
            is WriteBlock -> {
                shiftPointer(block.workingOffset)

                val refsToCache = mutableSetOf<Int>()
                val refsUsed = mutableSetOf<Int>()
                val written = mutableSetOf<Int>()
                for (write in block.writes) {
                    for (term in write.expr.terms) {
                        if (term.coeff == 0) continue
                        for (off in term.offsets) {
                            if ((off !in refsToCache && off in written) || !refsUsed.add(off)) {
                                refsToCache += off
                            }
                        }
                    }
                    written += write.offset
                }

                val localByRef = mutableMapOf<Int, LocalVar>()
                for ((i, ref) in refsToCache.withIndex()) {
                    val local = local<Int>(scratchBase + i)
                    localByRef[ref] = local
                    loadCell(ref)
                    store(local)
                }

                for (write in block.writes) {
                    load(tape)
                    load(pointer)
                    addOffset(write.offset)
                    writeExpr(write.expr, localByRef)
                    bastore
                }

                shiftPointer(block.pointerDelta - block.workingOffset)
            }

            is IOBlock -> {
                for (op in block.ops) {
                    when (op) {
                        is Output -> {
                            load(output)
                            writeExpr(op.expr, emptyMap())
                            invokevirtual<Writer>("write", desc<Void>(type<Int>()))
                        }
                        is Input -> {
                            load(tape)
                            load(pointer)
                            addOffset(op.offset)
                            load(input)
                            invokevirtual<Reader>("read", desc<Int>())
                            bastore
                        }
                    }
                }
                shiftPointer(block.pointerDelta)
            }

            is Loop -> {
                val loopStart = Label()
                val loopEnd = Label()
                mark(loopStart)
                // if (tape[pointer] == 0) break
                loadCell(0)
                ifeq(loopEnd)

                if (block.body.any { it is Loop }) {
                    val methodName = makeLoopBody(block) { writeBlock(it) }

                    // call the loop body
                    load(input)
                    load(output)
                    load(tape)
                    load(pointer)
                    invokestatic(className, methodName, loopMethodDescriptor)
                    store(pointer)
                } else {
                    for (op in block.body) {
                        writeBlock(op)
                    }
                }

                // jump back to the start of the loop
                goto(loopStart)
                mark(loopEnd)
            }
        }

        for (op in program) {
            mw.writeBlock(op)
        }

        mw.parameters("in", "out")
        mw.locals(
            tape to "tape",
            pointer to "pointer",
        )

        mw.areturn<Void>()
        mw.visitMaxs(0, 0)
        mw.visitEnd()
        cw.visitEnd()

        val bytes = cw.toByteArray()

        if (System.getenv("BF_EXPORT") != null) {
            val path = Path(".bf.out").resolve("${className}.class")
            path.parent.createDirectories()
            path.writeBytes(bytes)
        }

        warnCodeSize(bytes)

        val cl = loadClass(bytes)
        val lookup = MethodHandles.lookup()
        val handle = lookup.findStatic(cl, "run", mtype<Void>(type<Reader>(), type<Writer>()).methodType)
        val lambda = convertHandle(handle)
        return BfExecutable { input, output -> lambda(input.reader(), output.writer()) }
            .also { cache[program.toList()] = it }
    }

    private val cache: MutableMap<List<BfBlockOp>, BfExecutable> = mutableMapOf()
}

private fun loadClass(bytes: ByteArray): Class<*> {
    val name = ClassReader(bytes).className.replace('/', '.')
    val cl = object : ClassLoader() {
        override fun findClass(name: String): Class<*> {
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    return cl.loadClass(name)
}

private fun convertHandle(handle: MethodHandle): (Reader, Writer) -> Unit {
    assert(handle.type().returnType() == Void.TYPE)
    assert(handle.type().parameterCount() == 2)
    assert(handle.type().parameterType(0) == Reader::class.java)
    assert(handle.type().parameterType(1) == Writer::class.java)
    return { reader, writer -> handle.invoke(reader, writer) }
}

private fun warnCodeSize(clazz: ByteArray) {
    var maxSize = "" to 0
    val cn = ClassNode().also { ClassReader(clazz).accept(it, 0) }
    for (method in cn.methods) {
        val evaluator = CodeSizeEvaluator(null)
        method.accept(evaluator)
        if (evaluator.maxSize > 1024 * 8) {
            System.err.println(
                "Warning: Method ${cn.name}.${method.name} won't get jit without -XX:-DontCompileHugeMethods, " +
                        "because it is too large (${evaluator.maxSize} bytes). "
            )
        }
        if (evaluator.maxSize > maxSize.second) {
            maxSize = method.name to evaluator.maxSize
        }
    }
}

private fun verifyClass(clazz: ByteArray) {
    try {
        CheckClassAdapter.verify(ClassReader(clazz), false, PrintWriter(Writer.nullWriter()))
    } catch (_: AnalyzerException) {
        CheckClassAdapter.verify(ClassReader(clazz), true, PrintWriter(System.err))
    }
}
