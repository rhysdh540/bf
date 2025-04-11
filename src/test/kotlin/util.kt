import bf.BFOperation
import bf.bfStringify
import kotlin.test.assertEquals

fun bfAssertEquals(programA: Iterable<BFOperation>, programB: Iterable<BFOperation>) {
    assertEquals(bfStringify(programA), bfStringify(programB))
}