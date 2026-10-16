package dev.turbodash.core

import dev.turbodash.core.expr.EvalContext
import dev.turbodash.core.expr.Expression
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionTest {

    private val ctx = object : EvalContext {
        val vars = mapOf("rpm" to 3000.0, "status1" to 0x84.toDouble(), "alternate" to 1.0, "algorithm" to 1.0, "afrload" to 0.0)
        override fun value(name: String) = vars[name]
        override fun options(name: String) = if (name == "algorithmUnits") listOf("%", "kPa", "Baro") else null
    }

    private fun n(src: String) = Expression.compile(src).evalNumber(ctx)

    @Test
    fun `arithmetic follows C precedence`() {
        assertEquals(7.0, n("1 + 2 * 3"))
        assertEquals(9.0, n("(1 + 2) * 3"))
        assertEquals(20.0, n("60000.0 / rpm"))
        assertEquals(-4.0, n("-(2+2)"))
        assertEquals(1.0, n("10 % 3"))
    }

    @Test
    fun `bitwise logic and ternary`() {
        assertEquals(4.0, n("status1 & 4"))
        assertEquals(0.0, n("status1 & 1"))
        assertEquals(2.0, n("alternate ? 2 : 1"))
        assertEquals(1.0, n("rpm > 1000 && !(status1 & 1)"))
        assertEquals(3.0, n("rpm < 100 ? 1 : rpm < 1000 ? 2 : 3"))
        assertEquals(16.0, n("0x10"))
    }

    @Test
    fun `division by zero is zero and unknown names are zero`() {
        assertEquals(0.0, n("rpm / 0"))
        assertEquals(0.0, n("doesNotExist * 5"))
    }

    @Test
    fun `bitStringValue picks a label`() {
        val v = Expression.compile("bitStringValue( algorithmUnits ,  (afrload ? afrload : algorithm) )").eval(ctx)
        assertEquals("kPa", v)
    }
}
