package com.ingsis.snippet

import com.ingsis.snippet.observability.withLogContext
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogContextTest {
    @Test
    fun `agrega el contexto mientras corre y despues deja el MDC como estaba`() {
        MDC.put("snippetId", "afuera")
        try {
            val seen = withLogContext("snippetId" to "adentro", "testId" to 7, "nada" to null) { MDC.getCopyOfContextMap() }

            assertEquals(mapOf("snippetId" to "adentro", "testId" to "7"), seen)
            assertEquals("afuera", MDC.get("snippetId"))
            assertNull(MDC.get("testId"))
        } finally {
            MDC.clear()
        }
    }
}
