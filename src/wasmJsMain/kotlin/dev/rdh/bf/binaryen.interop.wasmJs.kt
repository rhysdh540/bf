@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.rdh.bf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsModule
import kotlin.js.JsName
import kotlin.js.Promise
import kotlin.js.definedExternally
import kotlin.js.toJsArray
import kotlin.js.toJsNumber

@JsModule("binaryen")
@JsName("default")
internal external val binaryen: BinaryenNamespace

internal typealias BinaryenType = Int
internal typealias BinaryenExprRef = Int
internal typealias BinaryenFunctionRef = Int
internal typealias BinaryenGlobalRef = Int

internal external interface BinaryenNamespace : JsAny {
    val ready: Promise<JsAny?>
    val none: BinaryenType
    val i32: BinaryenType
    val i64: BinaryenType
    val f32: BinaryenType
    val f64: BinaryenType
    val auto: BinaryenType
    val unreachable: BinaryenType
    val Features: BinaryenFeatures

    fun createType(types: JsArray<JsNumber>): BinaryenType
}

internal external interface BinaryenFeatures : JsAny {
    val MVP: Int
    val All: Int
    val BulkMemory: Int
    val BulkMemoryOpt: Int
}

@JsName("Module")
internal external class BinaryenModule : JsAny {
    fun setFeatures(features: Int)

    fun addFunctionImport(
        internalName: String,
        externalModuleName: String,
        externalBaseName: String,
        params: BinaryenType,
        results: BinaryenType,
    )

    fun addFunctionExport(internalName: String, externalName: String)

    fun addFunction(
        name: String,
        params: BinaryenType,
        results: BinaryenType,
        vars: JsArray<JsNumber>,
        body: BinaryenExprRef,
    ): BinaryenFunctionRef

    fun block(
        label: String?,
        children: JsArray<JsNumber>,
        resultType: BinaryenType? = definedExternally,
    ): BinaryenExprRef

    fun call(
        name: String,
        operands: JsArray<JsNumber>,
        returnType: BinaryenType,
    ): BinaryenExprRef

    fun setMemory(
        initial: Int,
        maximum: Int,
        exportName: String? = definedExternally,
        segments: JsArray<BinaryenMemorySegment>? = definedExternally,
        shared: Boolean? = definedExternally,
        memory64: Boolean? = definedExternally,
        internalName: String? = definedExternally,
    )

    // addGlobal(name: string, type: Type, mutable: boolean, init: ExpressionRef): GlobalRef;
    fun addGlobal(
        name: String,
        type: BinaryenType,
        mutable: Boolean,
        init: BinaryenExprRef,
    ): BinaryenGlobalRef

    @JsName("return")
    fun ret(value: BinaryenExprRef = definedExternally): BinaryenExprRef

    fun nop(): BinaryenExprRef

    @JsName("br_if")
    fun brIf(label: String, condition: BinaryenExprRef? = definedExternally, value: BinaryenExprRef? = definedExternally): BinaryenExprRef
    fun loop(label: String? = definedExternally, body: BinaryenExprRef): BinaryenExprRef
    fun `if`(condition: BinaryenExprRef, ifTrue: BinaryenExprRef, ifFalse: BinaryenExprRef? = definedExternally): BinaryenExprRef
    fun br(label: String, condition: BinaryenExprRef? = definedExternally, value: BinaryenExprRef? = definedExternally): BinaryenExprRef

    fun validate(): Int
    fun optimize()
    fun emitText(): String
    fun emitBinary(): JsAny
    fun dispose()

    val local: BinaryenLocalOps
    val global: BinaryenGlobalOps
    val i32: BinaryenI32Ops
    val memory: BinaryenMemoryOps
}

internal external interface BinaryenMemorySegment : JsAny {
    val offset: BinaryenExprRef
    val data: String
    val passive: Boolean
}

internal external interface BinaryenLocalOps : JsAny {
    fun get(index: Int, type: BinaryenType): BinaryenExprRef
    fun set(index: Int, value: BinaryenExprRef): BinaryenExprRef
    fun tee(index: Int, value: BinaryenExprRef, type: BinaryenType): BinaryenExprRef
}

internal external interface BinaryenGlobalOps : JsAny {
    fun get(name: String, type: BinaryenType): BinaryenExprRef
    fun set(name: String, value: BinaryenExprRef): BinaryenExprRef
}

internal external interface BinaryenMemoryOps : JsAny {
    fun size(name: String? = definedExternally, memory64: Boolean? = definedExternally): BinaryenExprRef
    fun grow(value: BinaryenExprRef, name: String? = definedExternally, memory64: Boolean? = definedExternally): BinaryenExprRef
    fun init(segment: Int, dest: BinaryenExprRef, offset: BinaryenExprRef, size: BinaryenExprRef, name: String? = definedExternally): BinaryenExprRef
    fun copy(dest: BinaryenExprRef, source: BinaryenExprRef, size: BinaryenExprRef, destName: String? = definedExternally, sourceName: String? = definedExternally): BinaryenExprRef
    fun fill(dest: BinaryenExprRef, value: BinaryenExprRef, size: BinaryenExprRef, name: String? = definedExternally): BinaryenExprRef
}

internal external interface BinaryenI32Ops : JsAny {
    @JsName("const")
    fun const(value: Int): BinaryenExprRef

    fun add(left: BinaryenExprRef, right: BinaryenExprRef): BinaryenExprRef
    fun sub(left: BinaryenExprRef, right: BinaryenExprRef): BinaryenExprRef
    fun mul(left: BinaryenExprRef, right: BinaryenExprRef): BinaryenExprRef
    fun eqz(value: BinaryenExprRef): BinaryenExprRef
    fun eq(left: BinaryenExprRef, right: BinaryenExprRef): BinaryenExprRef
    fun ne(left: BinaryenExprRef, right: BinaryenExprRef): BinaryenExprRef

    @JsName("load8_u")
    fun load8U(offset: Int, align: Int, ptr: BinaryenExprRef, name: String? = definedExternally): BinaryenExprRef

    @JsName("store8")
    fun store8(offset: Int, align: Int, ptr: BinaryenExprRef, value: BinaryenExprRef, name: String? = definedExternally): BinaryenExprRef
}

internal fun BinaryenNamespace.newModule(): BinaryenModule {
    return newModule(this)
}

@Suppress("UNUSED_PARAMETER")
private fun newModule(ns: BinaryenNamespace): BinaryenModule = js("new ns.Module()")

internal fun Iterable<Number>.toJs(): JsArray<JsNumber> {
    return this.map {
        when (it) {
            is Byte, is Short, is Int -> it.toInt().toJsNumber()
            is Long, is Float, is Double -> it.toDouble().toJsNumber()
            else -> throw IllegalArgumentException("Unsupported number type: ${it::class}")
        }
    }.toTypedArray().toJsArray()
}

internal fun BinaryenNamespace.createType(types: List<BinaryenType>): BinaryenType {
    return createType(types.toJs())
}

internal fun binaryenNumberArrayOf(vararg values: Int): JsArray<JsNumber> {
    return values.toList().toJs()
}

internal fun jsArrayOf(vararg values: JsAny): JsArray<JsAny> {
    return values.toList().toJsArray()
}

internal fun BinaryenModule.addFunction(
    name: String,
    params: BinaryenType,
    results: BinaryenType,
    vars: List<BinaryenType>,
    body: BinaryenExprRef,
): BinaryenFunctionRef {
    return addFunction(name, params, results, binaryenNumberArrayOf(*vars.toIntArray()), body)
}

internal fun BinaryenModule.block(
    label: String? = null,
    children: List<BinaryenExprRef>,
    resultType: BinaryenType? = null,
): BinaryenExprRef {
    val jsChildren = binaryenNumberArrayOf(*children.toIntArray())
    return if (resultType == null) {
        block(label, jsChildren)
    } else {
        block(label, jsChildren, resultType)
    }
}

internal fun BinaryenModule.call(
    name: String,
    operands: List<BinaryenExprRef>,
    returnType: BinaryenType,
): BinaryenExprRef {
    return call(name, binaryenNumberArrayOf(*operands.toIntArray()), returnType)
}
