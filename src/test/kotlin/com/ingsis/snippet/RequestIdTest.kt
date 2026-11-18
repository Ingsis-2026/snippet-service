package com.ingsis.snippet

import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.observability.REQUEST_ID_HEADER
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/** Los filtros del id del pedido y del usuario, con la cadena de Spring Security de verdad. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val telemetry: RecordingTelemetry,
) {
    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @BeforeEach
    fun setUp() = telemetry.clear()

    @Test
    fun `la respuesta lleva el id del pedido`() {
        mockMvc
            .get("/snippets") {
                with(asUser("auth0|ana"))
                header(REQUEST_ID_HEADER, "abc-123")
            }.andExpect {
                status { isOk() }
                header { string(REQUEST_ID_HEADER, "abc-123") }
            }
    }

    @Test
    fun `un pedido rechazado por falta de token tambien tiene id`() {
        mockMvc.get("/snippets").andExpect {
            status { isUnauthorized() }
            header { exists(REQUEST_ID_HEADER) }
        }
    }

    @Test
    fun `el usuario se toma despues de validar el token`() {
        mockMvc.get("/snippets") { with(asUser("auth0|ana")) }

        assertEquals(listOf("auth0|ana"), telemetry.users())
    }
}
