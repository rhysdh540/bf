package dev.rdh.bf

import org.objectweb.asm.*
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
import kotlin.math.absoluteValue
import kotlin.random.Random

@JvmName("compile")
@OptIn(ExperimentalStdlibApi::class)
fun bfCompile(program: Iterable<BFAffineOp>, opts: SystemRunnerOptions): (Reader, Writer) -> Unit {
    val className = "BFProgram$${Random.nextInt().toHexString()}"
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(V1_5, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

    if (opts.executable) {
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

            if (opts.debugInfo) {
                parameter("args")
            }
        }
    }

    val tapeSizeIsPowerOf2 = TAPE_SIZE and (TAPE_SIZE - 1) == 0

    // method to wrap negative indices
    if (opts.overflowProtection && !tapeSizeIsPowerOf2) {
        cw.method(ACC_PRIVATE or ACC_STATIC, "w", "(II)I") {
            // method signature: private static int w(int num, int length)
            // return num < 0 ? num + length : num

            val negative = Label()
            load<Int>(0)
            iflt(negative)
            load<Int>(0)
            areturn<Int>()
            mark(negative)
            load<Int>(0)
            load<Int>(1)
            iadd
            areturn<Int>()

            if (opts.debugInfo) {
                parameters("num", "length")
            }
        }
    }

    val mw = cw.method(name = "run", descriptor = desc<Void>(type<Reader>(), type<Writer>()))
    mw.visitCode()

    val input = mw.local<Reader>(0)
    val output = mw.local<Writer>(1)

    val tape = mw.local<ByteArray>(2)
    mw.int(TAPE_SIZE)
    mw.newarray<Byte>()
    mw.store(tape)

    // initialize pointer: int
    val pointer = mw.local<Int>(3)
    mw.int(TAPE_SIZE / 2)
    mw.store(pointer)

    fun MethodVisitor.addOffset(offset: Int) {
        if (offset == 0) return
        int(offset.absoluteValue)
        if (offset >= 0) iadd else isub

        if (opts.overflowProtection) {
            if (tapeSizeIsPowerOf2) {
                int(TAPE_SIZE - 1)
                iand
            } else {
                int(TAPE_SIZE)
                irem

                if (offset < 0) {
                    load(tape)
                    arraylength
                    invokestatic(className, "w", "(II)I")
                }
            }
        }
    }

    val scratchBase = 4

    // bf code has a lot of repeated loops, so we can reuse the same method
    val loopCache = mutableMapOf<BFAffineLoop, String>()
    var loopI = 1
    val loopMethodDescriptor = desc<Int>(type<Reader>(), type<Writer>(), type<ByteArray>(), type<Int>())

    // loop bodies go in separate functions, because the jvm can't handle large methods well
    fun makeLoopBody(loop: BFAffineLoop, writeOp: MethodVisitor.(BFAffineOp) -> Unit): String {
        return loopCache.getOrPut(loop) {
            val methodName = "loop$loopI"
            loopI++
            cw.method(ACC_PRIVATE or ACC_STATIC, methodName, loopMethodDescriptor) {
                for (op in loop) {
                    writeOp(op)
                }

                load(pointer)
                areturn<Int>()

                if (opts.debugInfo) {
                    parameters("in", "out", "tape", "pointer")
                }
            }
            methodName
        }
    }

    fun MethodVisitor.shiftPointer(delta: Int) {
        when {
            delta == 0 -> Unit
            !opts.overflowProtection && delta in Short.MIN_VALUE..Short.MAX_VALUE -> inc(pointer, delta)
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

    fun MethodVisitor.writeExpr(expr: BFAffineExpr, localByRef: Map<Int, LocalVar>) {
        int(expr.constant)
        for (term in expr.terms) {
            if (term.coefficient == 0) continue
            load(localByRef[term.offset] ?: error("Missing local for ref offset ${term.offset}"))
            if (term.coefficient.absoluteValue != 1) {
                int(term.coefficient.absoluteValue)
                imul
            }
            if (term.coefficient > 0) iadd else isub
        }
    }

    fun MethodVisitor.writeSegment(segment: BFAffineSegment): Unit = when (segment) {
        is BFAffineWriteBatch -> {
            val localByRef = mutableMapOf<Int, LocalVar>()
            for ((i, ref) in segment.refs.withIndex()) {
                val local = local<Int>(scratchBase + i)
                localByRef[ref] = local
                loadCell(ref)
                store(local)
            }

            for (write in segment.writes) {
                load(tape)
                load(pointer)
                addOffset(write.offset)
                writeExpr(write.expr, localByRef)
                bastore
            }
        }

        is BFAffineOutput -> {
            load(output)
            loadCell(segment.offset)
            int(0xFF)
            iand
            invokevirtual<Writer>("write", desc<Void>(type<Int>()))
        }

        is BFAffineInput -> {
            load(tape)
            load(pointer)
            addOffset(segment.offset)
            load(input)
            invokevirtual<Reader>("read", desc<Int>())
            bastore
        }
    }

    fun MethodVisitor.writeOp(op: BFAffineOp): Unit = when (op) {
        is BFAffineBlock -> {
            shiftPointer(op.baseShift)
            for (segment in op.segments) {
                writeSegment(segment)
            }
            shiftPointer(op.pointerDelta - op.baseShift)
        }

        is BFAffineLoop -> {
            val loopStart = Label()
            val loopEnd = Label()
            mark(loopStart)
            // if (tape[pointer] == 0) break
            loadCell(0)
            ifeq(loopEnd)

            if (op.any { it is BFAffineLoop }) {
                val methodName = makeLoopBody(op) { writeOp(it) }

                // call the loop body
                load(input)
                load(output)
                load(tape)
                load(pointer)
                invokestatic(className, methodName, loopMethodDescriptor)
                store(pointer)
            } else {
                for (op in op) {
                    writeOp(op)
                }
            }

            // jump back to the start of the loop
            goto(loopStart)
            mark(loopEnd)
        }
    }

    for (op in program) {
        mw.writeOp(op)
    }

    if (opts.debugInfo) {
        mw.parameters("in", "out")
        mw.locals(
            tape to "tape",
            pointer to "pointer",
        )
    }

    mw.areturn<Void>()
    mw.visitMaxs(0, 0)
    mw.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()

    if (opts.export) {
        val path = Path(".bf.out").resolve("${className}.class")
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    warnCodeSize(bytes)

    val cl = loadClass(bytes)
    val lookup = MethodHandles.lookup()
    val method = lookup.findStatic(cl, "run", mtype<Void>(type<Reader>(), type<Writer>()).methodType)
    return convertHandle(method)
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
