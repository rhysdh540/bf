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

    fun createType(types: JsArray<kotlin.js.JsNumber>): BinaryenType
}

internal external interface BinaryenFeatures : JsAny {
    val MVP: Int
    val All: Int
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
        vars: JsArray<kotlin.js.JsNumber>,
        body: BinaryenExprRef,
    ): BinaryenFunctionRef

    fun block(
        label: String?,
        children: JsArray<kotlin.js.JsNumber>,
        resultType: BinaryenType? = definedExternally,
    ): BinaryenExprRef

    fun call(
        name: String,
        operands: JsArray<kotlin.js.JsNumber>,
        returnType: BinaryenType,
    ): BinaryenExprRef

    @JsName("return")
    fun ret(value: BinaryenExprRef = definedExternally): BinaryenExprRef

    fun nop(): BinaryenExprRef

    fun validate(): Int
    fun optimize()
    fun emitText(): String
    fun emitBinary(): JsAny
    fun dispose()

    val local: BinaryenLocalOps
    val global: BinaryenGlobalOps
    val i32: BinaryenI32Ops
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

internal fun BinaryenNamespace.newModule(): BinaryenModule = newBinaryenModule(this)

@OptIn(ExperimentalWasmJsInterop::class)
private fun newBinaryenModule(ns: BinaryenNamespace): BinaryenModule = js("new ns.Module()")

internal fun BinaryenNamespace.createType(types: List<BinaryenType>): BinaryenType {
    return createType(types.map { it.toJsNumber() }.toTypedArray().toJsArray())
}

internal fun binaryenNumberArrayOf(vararg values: Int): JsArray<kotlin.js.JsNumber> {
    return values.map { it.toJsNumber() }.toTypedArray().toJsArray()
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
