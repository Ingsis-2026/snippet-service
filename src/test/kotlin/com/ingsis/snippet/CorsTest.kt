package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.snippets.USER_HEADER
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options

/**
 * Lo que le responde la API al navegador cuando la llama JavaScript de otro origin. curl y Postman
 * no aplican CORS: por eso se prueba con los headers que manda el navegador.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val localUi = "http://localhost:5173"

    /** El preflight: antes del request real, el navegador pregunta si lo puede hacer. */
    private fun preflight(
        origin: String,
        method: String = "POST",
        headers: String = "content-type,x-user-id",
    ) = mockMvc.options("/snippets") {
        header(HttpHeaders.ORIGIN, origin)
        header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
        header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers)
    }

    @Test
    fun `la UI en local puede crear un snippet mandando su usuario`() {
        preflight(localUi).andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, localUi) }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE") }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type, x-user-id") }
            header { string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600") }
        }
    }

    @Test
    fun `tambien puede mandar el token de Auth0`() {
        preflight(localUi, method = "DELETE", headers = "authorization").andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization") }
        }
    }

    @Test
    fun `un origin que no esta en la lista no puede`() {
        preflight("https://gatitos-gratis.com").andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    @Test
    fun `el mismo host con otro puerto es otro origin`() {
        preflight("http://localhost:3000").andExpect { status { isForbidden() } }
    }

    @Test
    fun `un metodo que la API no usa no se permite`() {
        preflight(localUi, method = "PATCH").andExpect { status { isForbidden() } }
    }

    @Test
    fun `un header que la API no espera no se permite`() {
        preflight(localUi, headers = "x-otra-cosa").andExpect { status { isForbidden() } }
    }

    @Test
    fun `la respuesta real dice que ese origin la puede leer y deja ver el nombre del archivo`() {
        mockMvc
            .get("/rules/formatting") {
                header(HttpHeaders.ORIGIN, localUi)
                header(USER_HEADER, "auth0|ana")
            }.andExpect {
                status { isOk() }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, localUi) }
                header { string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION) }
            }
    }

    @Test
    fun `un request sin Origin, como el de la UI desplegada por nginx o curl, no lleva headers de CORS`() {
        mockMvc.get("/rules/formatting") { header(USER_HEADER, "auth0|ana") }.andExpect {
            status { isOk() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    /** En las VMs la lista llega por `CORS_ALLOWED_ORIGINS`, separada por comas. */
    @Nested
    @SpringBootTest(properties = ["cors.allowed-origins=https://grupo-dev.duckdns.org,https://grupo.duckdns.org"])
    inner class ConOriginsConfigurados(
        @Autowired private val mockMvc: MockMvc,
    ) {
        @Test
        fun `acepta cada origin de la lista`() {
            for (origin in listOf("https://grupo-dev.duckdns.org", "https://grupo.duckdns.org")) {
                mockMvc
                    .options("/snippets") {
                        header(HttpHeaders.ORIGIN, origin)
                        header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    }.andExpect {
                        status { isOk() }
                        header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin) }
                    }
            }
        }

        @Test
        fun `el default de desarrollo deja de valer`() {
            mockMvc
                .options("/snippets") {
                    header(HttpHeaders.ORIGIN, "http://localhost:5173")
                    header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                }.andExpect { status { isForbidden() } }
        }
    }
}
