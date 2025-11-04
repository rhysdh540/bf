@file:Suppress("NOTHING_TO_INLINE", "unused")

package bf

// inline boilerplate galore

import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.Opcodes.*

// region method calls
inline fun MethodVisitor.invokestatic(owner: String, name: String, descriptor: String, isInterface: Boolean = false) {
    visitMethodInsn(INVOKESTATIC, owner, name, descriptor, isInterface)
}

inline fun MethodVisitor.invokestatic(owner: Type, name: String, descriptor: String, isInterface: Boolean = false) {
    invokestatic(owner.internalName, name, descriptor, isInterface)
}

inline fun <reified T> MethodVisitor.invokestatic(name: String, descriptor: String, isInterface: Boolean = false) {
    invokestatic(type<T>(), name, descriptor, isInterface)
}

inline fun MethodVisitor.invokevirtual(owner: String, name: String, descriptor: String, isInterface: Boolean = false) {
    visitMethodInsn(INVOKEVIRTUAL, owner, name, descriptor, isInterface)
}

inline fun MethodVisitor.invokevirtual(owner: Type, name: String, descriptor: String, isInterface: Boolean = false) {
    invokevirtual(owner.internalName, name, descriptor, isInterface)
}

inline fun <reified T> MethodVisitor.invokevirtual(name: String, descriptor: String, isInterface: Boolean = false) {
    invokevirtual(type<T>(), name, descriptor, isInterface)
}

inline fun MethodVisitor.invokespecial(owner: String, name: String, descriptor: String, isInterface: Boolean = false) {
    visitMethodInsn(INVOKESPECIAL, owner, name, descriptor, isInterface)
}

inline fun MethodVisitor.invokespecial(owner: Type, name: String, descriptor: String, isInterface: Boolean = false) {
    invokespecial(owner.internalName, name, descriptor, isInterface)
}

inline fun <reified T> MethodVisitor.invokespecial(name: String, descriptor: String, isInterface: Boolean = false) {
    invokespecial(type<T>(), name, descriptor, isInterface)
}

inline fun MethodVisitor.invokeinterface(owner: String, name: String, descriptor: String) {
    visitMethodInsn(INVOKEINTERFACE, owner, name, descriptor, true)
}

inline fun MethodVisitor.invokeinterface(owner: Type, name: String, descriptor: String) {
    invokeinterface(owner.internalName, name, descriptor)
}

inline fun <reified T> MethodVisitor.invokeinterface(name: String, descriptor: String) {
    invokeinterface(type<T>(), name, descriptor)
}

inline fun MethodVisitor.invokedynamic(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) {
    visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
}

inline fun handle(
    tag: Int,
    owner: String,
    name: String,
    descriptor: String,
    isInterface: Boolean = tag == H_INVOKEINTERFACE
): Handle {
    return Handle(tag, owner, name, descriptor, isInterface)
}

inline fun handle(
    tag: Int,
    owner: Type,
    name: String,
    descriptor: String,
    isInterface: Boolean = tag == H_INVOKEINTERFACE
): Handle {
    return handle(tag, owner.internalName, name, descriptor, isInterface)
}

inline fun <reified T> handle(
    tag: Int,
    name: String,
    descriptor: String,
    isInterface: Boolean = tag == H_INVOKEINTERFACE
): Handle {
    return handle(tag, type<T>(), name, descriptor, isInterface)
}

// endregion

// region field operations
inline fun MethodVisitor.getstatic(owner: String, name: String, descriptor: String) {
    visitFieldInsn(GETSTATIC, owner, name, descriptor)
}

inline fun MethodVisitor.getstatic(owner: Type, name: String, descriptor: String) {
    getstatic(owner.internalName, name, descriptor)
}

inline fun <reified T> MethodVisitor.getstatic(name: String, descriptor: String) {
    getstatic(type<T>(), name, descriptor)
}

inline fun MethodVisitor.putstatic(owner: String, name: String, descriptor: String) {
    visitFieldInsn(PUTSTATIC, owner, name, descriptor)
}

inline fun MethodVisitor.putstatic(owner: Type, name: String, descriptor: String) {
    putstatic(owner.internalName, name, descriptor)
}

inline fun <reified T> MethodVisitor.putstatic(name: String, descriptor: String) {
    putstatic(type<T>(), name, descriptor)
}

inline fun MethodVisitor.getfield(owner: String, name: String, descriptor: String) {
    visitFieldInsn(GETFIELD, owner, name, descriptor)
}

inline fun MethodVisitor.getfield(owner: Type, name: String, descriptor: String) {
    getfield(owner.internalName, name, descriptor)
}

inline fun <reified T> MethodVisitor.getfield(name: String, descriptor: String) {
    getfield(type<T>(), name, descriptor)
}

inline fun MethodVisitor.putfield(owner: String, name: String, descriptor: String) {
    visitFieldInsn(PUTFIELD, owner, name, descriptor)
}

inline fun MethodVisitor.putfield(owner: Type, name: String, descriptor: String) {
    putfield(owner.internalName, name, descriptor)
}

inline fun <reified T> MethodVisitor.putfield(name: String, descriptor: String) {
    putfield(type<T>(), name, descriptor)
}
// endregion

// region stack operations
inline val MethodVisitor.dup; get() = visitInsn(DUP)
inline val MethodVisitor.pop2; get() = visitInsn(POP2)
inline val MethodVisitor.pop; get() = visitInsn(POP)
inline val MethodVisitor.dup2; get() = visitInsn(DUP2)
inline val MethodVisitor.dupx1; get() = visitInsn(DUP_X1)
inline val MethodVisitor.dupx2; get() = visitInsn(DUP_X2)
inline val MethodVisitor.dup2x1; get() = visitInsn(DUP2_X1)
inline val MethodVisitor.dup2x2; get() = visitInsn(DUP2_X2)
inline val MethodVisitor.swap; get() = visitInsn(SWAP)
// endregion

// region local variable operations
inline fun MethodVisitor.load(opcode: Int = ALOAD, index: Int) = visitVarInsn(opcode, index)
inline fun MethodVisitor.load(type: Type, index: Int) = load(type.getOpcode(ILOAD), index)
inline fun <reified T> MethodVisitor.load(index: Int) = load(type<T>(), index)
inline fun MethodVisitor.store(opcode: Int = ASTORE, index: Int) = visitVarInsn(opcode, index)
inline fun MethodVisitor.store(type: Type, index: Int) = store(type.getOpcode(ISTORE), index)
inline fun <reified T> MethodVisitor.store(index: Int) = store(type<T>(), index)

inline fun MethodVisitor.areturn(type: Type) = visitInsn(type.getOpcode(IRETURN))
inline fun <reified T> MethodVisitor.areturn() = areturn(type<T>())
// endregion

// region type operations
inline fun MethodVisitor.new(type: Type) = visitTypeInsn(NEW, type.internalName)
inline fun <reified T> MethodVisitor.new() = new(type<T>())
inline fun MethodVisitor.checkcast(type: Type) = visitTypeInsn(CHECKCAST, type.internalName)
inline fun <reified T> MethodVisitor.checkcast() = checkcast(type<T>())
inline fun MethodVisitor.instanceof(type: Type) = visitTypeInsn(INSTANCEOF, type.internalName)
inline fun <reified T> MethodVisitor.instanceof() = instanceof(type<T>())

// region primitive conversions
inline val MethodVisitor.i2l; get() = visitInsn(I2L)
inline val MethodVisitor.i2f; get() = visitInsn(I2F)
inline val MethodVisitor.i2d; get() = visitInsn(I2D)
inline val MethodVisitor.l2i; get() = visitInsn(L2I)
inline val MethodVisitor.l2f; get() = visitInsn(L2F)
inline val MethodVisitor.l2d; get() = visitInsn(L2D)
inline val MethodVisitor.f2i; get() = visitInsn(F2I)
inline val MethodVisitor.f2l; get() = visitInsn(F2L)
inline val MethodVisitor.f2d; get() = visitInsn(F2D)
inline val MethodVisitor.d2i; get() = visitInsn(D2I)
inline val MethodVisitor.d2l; get() = visitInsn(D2L)
inline val MethodVisitor.d2f; get() = visitInsn(D2F)
inline val MethodVisitor.i2b; get() = visitInsn(I2B)
inline val MethodVisitor.i2c; get() = visitInsn(I2C)
inline val MethodVisitor.i2s; get() = visitInsn(I2S)
// endregion
// endregion

// region jumps
inline fun MethodVisitor.mark(label: Label) = visitLabel(label)
inline fun MethodVisitor.goto(label: Label) = visitJumpInsn(GOTO, label)
inline fun MethodVisitor.ifeq(label: Label) = visitJumpInsn(IFEQ, label)
inline fun MethodVisitor.ifne(label: Label) = visitJumpInsn(IFNE, label)
inline fun MethodVisitor.iflt(label: Label) = visitJumpInsn(IFLT, label)
inline fun MethodVisitor.ifge(label: Label) = visitJumpInsn(IFGE, label)
inline fun MethodVisitor.ifgt(label: Label) = visitJumpInsn(IFGT, label)
inline fun MethodVisitor.ifle(label: Label) = visitJumpInsn(IFLE, label)
inline fun MethodVisitor.ifnull(label: Label) = visitJumpInsn(IFNULL, label)
inline fun MethodVisitor.ifnonnull(label: Label) = visitJumpInsn(IFNONNULL, label)
inline fun MethodVisitor.ifacmpeq(label: Label) = visitJumpInsn(IF_ACMPEQ, label)
inline fun MethodVisitor.ifacmpne(label: Label) = visitJumpInsn(IF_ACMPNE, label)
inline fun MethodVisitor.ificmpeq(label: Label) = visitJumpInsn(IF_ICMPEQ, label)
inline fun MethodVisitor.ificmpne(label: Label) = visitJumpInsn(IF_ICMPNE, label)
inline fun MethodVisitor.ificmplt(label: Label) = visitJumpInsn(IF_ICMPLT, label)
inline fun MethodVisitor.ificmpge(label: Label) = visitJumpInsn(IF_ICMPGE, label)
inline fun MethodVisitor.ificmpgt(label: Label) = visitJumpInsn(IF_ICMPGT, label)
inline fun MethodVisitor.ificmple(label: Label) = visitJumpInsn(IF_ICMPLE, label)
// endregion

// region math
inline fun MethodVisitor.add(type: Type) = visitInsn(type.getOpcode(IADD))
inline fun <reified T> MethodVisitor.add() = add(type<T>())
inline val MethodVisitor.iadd; get() = visitInsn(IADD)
inline val MethodVisitor.ladd; get() = visitInsn(LADD)
inline val MethodVisitor.fadd; get() = visitInsn(FADD)
inline val MethodVisitor.dadd; get() = visitInsn(DADD)

inline fun MethodVisitor.sub(type: Type) = visitInsn(type.getOpcode(ISUB))
inline fun <reified T> MethodVisitor.sub() = sub(type<T>())
inline val MethodVisitor.isub; get() = visitInsn(ISUB)
inline val MethodVisitor.lsub; get() = visitInsn(LSUB)
inline val MethodVisitor.fsub; get() = visitInsn(FSUB)
inline val MethodVisitor.dsub; get() = visitInsn(DSUB)

inline fun MethodVisitor.mul(type: Type) = visitInsn(type.getOpcode(IMUL))
inline fun <reified T> MethodVisitor.mul() = mul(type<T>())
inline val MethodVisitor.imul; get() = visitInsn(IMUL)
inline val MethodVisitor.lmul; get() = visitInsn(LMUL)
inline val MethodVisitor.fmul; get() = visitInsn(FMUL)
inline val MethodVisitor.dmul; get() = visitInsn(DMUL)

inline fun MethodVisitor.div(type: Type) = visitInsn(type.getOpcode(IDIV))
inline fun <reified T> MethodVisitor.div() = div(type<T>())
inline val MethodVisitor.idiv; get() = visitInsn(IDIV)
inline val MethodVisitor.ldiv; get() = visitInsn(LDIV)
inline val MethodVisitor.fdiv; get() = visitInsn(FDIV)
inline val MethodVisitor.ddiv; get() = visitInsn(DDIV)

inline fun MethodVisitor.rem(type: Type) = visitInsn(type.getOpcode(IREM))
inline fun <reified T> MethodVisitor.rem() = rem(type<T>())
inline val MethodVisitor.irem; get() = visitInsn(IREM)
inline val MethodVisitor.lrem; get() = visitInsn(LREM)
inline val MethodVisitor.frem; get() = visitInsn(FREM)
inline val MethodVisitor.drem; get() = visitInsn(DREM)

inline fun MethodVisitor.neg(type: Type) = visitInsn(type.getOpcode(INEG))
inline fun <reified T> MethodVisitor.neg() = neg(type<T>())
inline val MethodVisitor.ineg; get() = visitInsn(INEG)
inline val MethodVisitor.lneg; get() = visitInsn(LNEG)
inline val MethodVisitor.fneg; get() = visitInsn(FNEG)
inline val MethodVisitor.dneg; get() = visitInsn(DNEG)

inline fun MethodVisitor.inc(index: Int, value: Int = 1) = visitIincInsn(index, value)

// region bitwise
inline fun MethodVisitor.and(type: Type) = visitInsn(type.getOpcode(IAND))
inline fun <reified T> MethodVisitor.and() = and(type<T>())
inline val MethodVisitor.iand; get() = visitInsn(IAND)
inline val MethodVisitor.land; get() = visitInsn(LAND)

inline fun MethodVisitor.or(type: Type) = visitInsn(type.getOpcode(IOR))
inline fun <reified T> MethodVisitor.or() = or(type<T>())
inline val MethodVisitor.ior; get() = visitInsn(IOR)
inline val MethodVisitor.lor; get() = visitInsn(LOR)

inline fun MethodVisitor.xor(type: Type) = visitInsn(type.getOpcode(IXOR))
inline fun <reified T> MethodVisitor.xor() = xor(type<T>())
inline val MethodVisitor.ixor; get() = visitInsn(IXOR)
inline val MethodVisitor.lxor; get() = visitInsn(LXOR)

inline fun MethodVisitor.shl(type: Type) = visitInsn(type.getOpcode(ISHL))
inline fun <reified T> MethodVisitor.shl() = shl(type<T>())
inline val MethodVisitor.ishl; get() = visitInsn(ISHL)
inline val MethodVisitor.lshl; get() = visitInsn(LSHL)

inline fun MethodVisitor.shr(type: Type) = visitInsn(type.getOpcode(ISHR))
inline fun <reified T> MethodVisitor.shr() = shr(type<T>())
inline val MethodVisitor.ishr; get() = visitInsn(ISHR)
inline val MethodVisitor.lshr; get() = visitInsn(LSHR)

inline fun MethodVisitor.ushr(type: Type) = visitInsn(type.getOpcode(IUSHR))
inline fun <reified T> MethodVisitor.ushr() = ushr(type<T>())
inline val MethodVisitor.iushr; get() = visitInsn(IUSHR)
inline val MethodVisitor.lushr; get() = visitInsn(LUSHR)
// endregion
// endregion

// region arrays
inline fun MethodVisitor.newarray(type: Type){
    if (type.sort == Type.OBJECT || type.sort == Type.ARRAY) {
        visitTypeInsn(ANEWARRAY, type.internalName)
    } else {
        visitIntInsn(NEWARRAY, when (type.sort) {
            Type.BOOLEAN -> T_BOOLEAN
            Type.CHAR -> T_CHAR
            Type.BYTE -> T_BYTE
            Type.SHORT -> T_SHORT
            Type.INT -> T_INT
            Type.FLOAT -> T_FLOAT
            Type.LONG -> T_LONG
            Type.DOUBLE -> T_DOUBLE
            else -> throw IllegalArgumentException("Invalid array type: $type")
        })
    }
}

inline fun <reified T> MethodVisitor.newarray() = newarray(type<T>())

inline val MethodVisitor.arraylength; get() = visitInsn(ARRAYLENGTH)
inline val MethodVisitor.iaload; get() = visitInsn(IALOAD)
inline val MethodVisitor.laload; get() = visitInsn(LALOAD)
inline val MethodVisitor.faload; get() = visitInsn(FALOAD)
inline val MethodVisitor.daload; get() = visitInsn(DALOAD)
inline val MethodVisitor.aaload; get() = visitInsn(AALOAD)
inline val MethodVisitor.baload; get() = visitInsn(BALOAD)
inline val MethodVisitor.caload; get() = visitInsn(CALOAD)
inline val MethodVisitor.saload; get() = visitInsn(SALOAD)

inline val MethodVisitor.iastore; get() = visitInsn(IASTORE)
inline val MethodVisitor.lastore; get() = visitInsn(LASTORE)
inline val MethodVisitor.fastore; get() = visitInsn(FASTORE)
inline val MethodVisitor.dastore; get() = visitInsn(DASTORE)
inline val MethodVisitor.aastore; get() = visitInsn(AASTORE)
inline val MethodVisitor.bastore; get() = visitInsn(BASTORE)
inline val MethodVisitor.castore; get() = visitInsn(CASTORE)
inline val MethodVisitor.sastore; get() = visitInsn(SASTORE)
// endregion