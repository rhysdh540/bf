package bf.opt

import bf.BFOperation
import bf.Input
import bf.PointerMove
import bf.Print
import bf.SetToConstant
import bf.ValueChange

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
                        newBlock.add(Input(offset = pointer))
                    }

                    is Print -> {
                        newBlock.addAll(currBlock)
                        currBlock.clear()
                        newBlock.add(Print(offset = pointer))
                    }

                    is ValueChange -> {
                        currBlock.add(ValueChange(offset = pointer, value = op.value))
                    }

                    is SetToConstant -> {
                        currBlock.add(SetToConstant(offset = pointer, value = op.value))
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

            program.subList(i, j).let {
                it.clear()
                it.addAll(newBlock)
            }

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