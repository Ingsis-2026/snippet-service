package com.ingsis.snippet

import com.ingsis.snippet.observability.REQUEST_ID
import com.ingsis.snippet.observability.REQUEST_ID_HEADER
import com.ingsis.snippet.observability.RequestIdFilter
import com.ingsis.snippet.observability.USER
import com.ingsis.snippet.observability.UserLogContextFilter
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Los filtros que ponen en el MDC el id del pedido y quién lo hace, y lo sacan al terminar. */
class RequestIdFilterTest {
    private val response = MockHttpServletResponse()
    private val seen = mutableMapOf<String, String?>()
    private val recordingChain = FilterChain { _, _ -> seen.putAll(mapOf(REQUEST_ID to MDC.get(REQUEST_ID), USER to MDC.get(USER))) }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun requestWith(requestId: String?) = MockHttpServletRequest().apply { requestId?.let { addHeader(REQUEST_ID_HEADER, it) } }

    @Test
    fun `usa el id que manda nginx mientras atiende el pedido y lo devuelve en la respuesta`() {
        RequestIdFilter().doFilter(requestWith("abc-123"), response, recordingChain)

        assertEquals("abc-123", seen[REQUEST_ID])
        assertEquals("abc-123", response.getHeader(REQUEST_ID_HEADER))
        assertNull(MDC.get(REQUEST_ID))
    }

    @Test
    fun `si no viene un id genera uno`() {
        RequestIdFilter().doFilter(requestWith(null), response, recordingChain)

        val generated = response.getHeader(REQUEST_ID_HEADER)!!
        UUID.fromString(generated)
        assertEquals(generated, seen[REQUEST_ID])
    }

    @Test
    fun `un id que no parece un id se reemplaza por uno nuevo`() {
        RequestIdFilter().doFilter(requestWith("abc\nINFO falso"), response, recordingChain)

        assertNotEquals("abc\nINFO falso", seen[REQUEST_ID])
        UUID.fromString(seen[REQUEST_ID])
    }

    @Test
    fun `saca el id del MDC aunque el pedido falle`() {
        assertThrows<IllegalStateException> {
            RequestIdFilter().doFilter(requestWith("abc-123"), response) { _, _ -> throw IllegalStateException("falló") }
        }

        assertNull(MDC.get(REQUEST_ID))
    }

    @Test
    fun `agrega al MDC el sub del token y se lo cuenta a New Relic`() {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject("auth0|ana")
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        val telemetry = RecordingTelemetry()

        UserLogContextFilter(telemetry).doFilter(requestWith(null), response, recordingChain)

        assertEquals("auth0|ana", seen[USER])
        assertEquals(listOf("auth0|ana"), telemetry.users())
        assertNull(MDC.get(USER))
    }

    @Test
    fun `un pedido sin usuario pasa sin cambios`() {
        val telemetry = RecordingTelemetry()

        UserLogContextFilter(telemetry).doFilter(requestWith(null), response, recordingChain)

        assertNull(seen[USER])
        assertEquals(emptyList(), telemetry.users())
    }
}
