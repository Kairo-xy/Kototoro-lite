package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Cached method resolution must choose exactly what the uncached hierarchy walk chose, call after call. */
class MihonReflectionTest {
    open class Base {
        protected open fun visible(): String = "base"
        open fun shared(value: Any?): String = "base:any"
    }

    class Child : Base() {
        override fun visible(): String = "child"
        fun overloaded(value: String?): String = "string"
        fun overloaded(value: Int): String = "int"
        fun overloaded(value: Double): String = "double"
        fun primitive(value: Long): String = "long"
    }

    @Test
    fun `overloads follow each call's argument types`() {
        val target = Child()
        repeat(3) {
            assertEquals("string", MihonReflection.call(target, "overloaded", "text"))
            assertEquals("int", MihonReflection.call(target, "overloaded", 1))
            assertEquals("double", MihonReflection.call(target, "overloaded", 1.5))
        }
        // A null argument cannot select a primitive overload; only the reference one accepts it.
        assertEquals("string", MihonReflection.call(target, "overloaded", null))
        assertThrows(NoSuchMethodException::class.java) { MihonReflection.call(target, "primitive", null) }
        assertEquals("long", MihonReflection.call(target, "primitive", 2L))
    }

    @Test
    fun `overrides and inherited protected methods resolve per class`() {
        assertEquals("child", MihonReflection.call(Child(), "visible"))
        assertEquals("base", MihonReflection.call(Base(), "visible"))
        assertEquals("base:any", MihonReflection.call(Child(), "shared", "x"))
    }

    @Test
    fun `missing methods stay missing`() {
        repeat(3) { assertNull(MihonReflection.optional(Child(), "absent", "x")) }
        assertThrows(NoSuchMethodException::class.java) { MihonReflection.call(Child(), "absent") }
    }
}
