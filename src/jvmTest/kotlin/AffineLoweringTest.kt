import dev.rdh.bf.BFAffineBlock
import dev.rdh.bf.BFAffineInput
import dev.rdh.bf.BFAffineLoop
import dev.rdh.bf.BFAffineOutput
import dev.rdh.bf.BFAffineWrite
import dev.rdh.bf.BFAffineWriteBatch
import dev.rdh.bf.Copy
import dev.rdh.bf.Loop
import dev.rdh.bf.PointerMove
import dev.rdh.bf.Print
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange
import dev.rdh.bf.bfLowerAffine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AffineLoweringTest {
    @Test
    fun `normalizes offsets in a block`() {
        val lowered = bfLowerAffine(
            listOf(
                PointerMove(2),
                ValueChange(1),
                PointerMove(-3),
                ValueChange(2),
            )
        )

        val block = assertIs<BFAffineBlock>(lowered.single())
        assertEquals(-1, block.baseShift)
        assertEquals(-1, block.pointerDelta)

        val writeBatch = assertIs<BFAffineWriteBatch>(block.segments.single())
        assertEquals(listOf(0, 3), writeBatch.writes.map { it.offset })
        assertTrue(writeBatch.writes.all { it.offset >= 0 })
    }

    @Test
    fun `flushes around print and input barriers`() {
        val lowered = bfLowerAffine(
            listOf(
                ValueChange(1),
                Print(),
                ValueChange(2),
                dev.rdh.bf.Input(),
                ValueChange(3),
            )
        )

        val block = assertIs<BFAffineBlock>(lowered.single())
        assertEquals(0, block.baseShift)
        assertEquals(0, block.pointerDelta)
        assertEquals(5, block.segments.size)
        assertIs<BFAffineWriteBatch>(block.segments[0])
        assertIs<BFAffineOutput>(block.segments[1])
        assertIs<BFAffineWriteBatch>(block.segments[2])
        assertIs<BFAffineInput>(block.segments[3])
        assertIs<BFAffineWriteBatch>(block.segments[4])
    }

    @Test
    fun `coalesces copy operations into one writeback`() {
        val lowered = bfLowerAffine(
            listOf(
                Copy(multiplier = 1, offset = 1),
                Copy(multiplier = 1, offset = 2),
                SetToConstant(),
            )
        )

        val block = assertIs<BFAffineBlock>(lowered.single())
        val batch = assertIs<BFAffineWriteBatch>(block.segments.single())
        val writeByOffset = batch.writes.associateBy(BFAffineWrite::offset)
        assertEquals(setOf(0, 1, 2), writeByOffset.keys)

        val write0 = writeByOffset[0]!!
        assertEquals(0, write0.expr.constant)
        assertTrue(write0.expr.terms.isEmpty())

        val write1Terms = writeByOffset[1]!!.expr.terms.associate { it.offset to it.coefficient }
        val write2Terms = writeByOffset[2]!!.expr.terms.associate { it.offset to it.coefficient }
        assertEquals(mapOf(0 to 1, 1 to 1), write1Terms)
        assertEquals(mapOf(0 to 1, 2 to 1), write2Terms)
    }

    @Test
    fun `lowers loops recursively`() {
        val lowered = bfLowerAffine(
            listOf(
                Loop(
                    listOf(
                        ValueChange(1),
                    )
                )
            )
        )

        val loop = assertIs<BFAffineLoop>(lowered.single())
        assertEquals(1, loop.size)
        assertIs<BFAffineBlock>(loop.single())
    }
}
