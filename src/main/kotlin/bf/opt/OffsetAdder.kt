package bf.opt

import bf.BFOperation
import bf.Input
import bf.PointerMove
import bf.Print
import bf.SetToConstant
import bf.ValueChange
import bf.set

internal object OffsetAdder : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size) {
            while (i < program.size && !program[i].offsettable) {
                i++
            }

            if (i >= program.size) {
                break
            }

            var j = i

            while (j < program.size && program[j].offsettable) {
                j++
            }

            // the block [i..j) is a sequence of operations that can be replaced with offsets

            val newBlock = mutableListOf<BFOperation>()
            val currBlock = mutableListOf<BFOperation>()
            var pointer = 0

            for (k in i until j) {
                val op = program[k]
                if (op !is PointerMove) {
                    pointer += op.offset
                }

                when (op) {
                    is Input -> {
                        newBlock.addAll(currBlock)
                        currBlock.clear()
                        newBlock += Input(offset = pointer)
                    }

                    is Print -> {
                        newBlock.addAll(currBlock)
                        currBlock.clear()
                        newBlock += Print(offset = pointer)
                    }

                    is ValueChange -> {
                        currBlock += ValueChange(offset = pointer, value = op.value)
                    }

                    is SetToConstant -> {
                        currBlock += SetToConstant(offset = pointer, value = op.value)
                    }

                    is PointerMove -> {
                        pointer += op.value
                    }

                    else -> {
                        error("Unexpected operation: $op")
                    }
                }
            }

            newBlock.addAll(currBlock)
            if (pointer != 0) {
                newBlock.add(PointerMove(pointer))
            }

            // extremely specific case where there's a single PointerMove followed by an offsettable
            // so for the case of something like `>>>-`
            // instead of [ValueChange(3, -1), PointerMove(3)]
            // we get [PointerMove(3), ValueChange(-1)]
            if (newBlock.size == 2
                && newBlock[0].offsettable
                && newBlock[1] is PointerMove
                && newBlock[0].offset == (newBlock[1] as PointerMove).value
            ) {
                val first = newBlock[0]
                val pm = newBlock[1] as PointerMove
                newBlock.clear()
                newBlock += PointerMove(pm.value)
                newBlock += when (first) {
                    is Input -> Input()
                    is Print -> Print()
                    is ValueChange -> ValueChange(value = first.value)
                    is SetToConstant -> SetToConstant(value = first.value)
                    else -> error("Unexpected operation: $first")
                }
            }

            program[i..j] = newBlock

            i += newBlock.size + 1
        }
    }

    private val BFOperation.offsettable: Boolean
        get() = this is ValueChange
                || this is Print
                || this is Input
                || this is SetToConstant
                || this is PointerMove

    private val BFOperation.offset: Int
        get() = when (this) {
            is ValueChange -> this.offset
            is Print -> this.offset
            is Input -> this.offset
            is SetToConstant -> this.offset
            else -> error("Unexpected operation: $this")
        }
}