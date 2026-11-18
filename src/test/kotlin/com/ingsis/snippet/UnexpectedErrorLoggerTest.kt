package com.ingsis.snippet

import com.ingsis.snippet.observability.UnexpectedErrorLogger
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnexpectedErrorLoggerTest {
    @Test
    fun `un error inesperado se responde con un 500, como sin el logger`() {
        val response = MockHttpServletResponse()

        val view = UnexpectedErrorLogger().resolveException(MockHttpServletRequest(), response, null, IllegalStateException("se rompió"))

        assertEquals(500, response.status)
        assertTrue(view.isEmpty)
    }
}
