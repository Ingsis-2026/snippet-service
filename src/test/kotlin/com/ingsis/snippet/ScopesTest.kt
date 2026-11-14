package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID
import kotlin.test.assertTrue

/**
 * Además del token, cada endpoint pide un scope de Auth0: `read:snippets` para leer y correr,
 * `write:snippets` para crear, cambiar, borrar y compartir. Sin el scope es un 403, no un 401: el
 * token es válido, solo que no alcanza.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScopesTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    private val missing = UUID.randomUUID()
    private val readOnly = asUser("auth0|ana", READ_SNIPPETS)
    private val writeOnly = asUser("auth0|ana", WRITE_SNIPPETS)
    private val noScopes = asUser("auth0|ana", *emptyArray())

    @BeforeEach
    fun setUp() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", null)).thenReturn(emptyList())
    }

    private val snippetJson =
        """{"name": "Saludo", "description": "Saluda", "language": "printscript", "version": "1.1", "content": "println(1);"}"""

    /** Todo lo que cambia algo de lo que guardó el usuario. */
    private fun writes(token: RequestPostProcessor): List<ResultActionsDsl> =
        listOf(
            mockMvc.post("/snippets") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = snippetJson
            },
            mockMvc.put("/snippets/$missing") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = snippetJson
            },
            mockMvc.delete("/snippets/$missing") { with(token) },
            mockMvc.post("/snippets/$missing/share") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId": "auth0|beto"}"""
            },
            mockMvc.post("/snippets/$missing/tests") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "t"}"""
            },
            mockMvc.put("/snippets/$missing/tests/$missing") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "t"}"""
            },
            mockMvc.delete("/snippets/$missing/tests/$missing") { with(token) },
            mockMvc.put("/rules/linting") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = "[]"
            },
            mockMvc.put("/rules/formatting") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = "[]"
            },
        )

    /** Lo que solo lee, o corre algo sin cambiar lo que guardó el usuario. */
    private fun reads(token: RequestPostProcessor): List<ResultActionsDsl> =
        listOf(
            mockMvc.get("/snippets") { with(token) },
            mockMvc.get("/snippets/$missing") { with(token) },
            mockMvc.get("/snippets/$missing/tests") { with(token) },
            mockMvc.get("/snippets/$missing/lint") { with(token) },
            mockMvc.get("/snippets/$missing/download") { with(token) },
            mockMvc.get("/rules/linting") { with(token) },
            mockMvc.get("/rules/formatting") { with(token) },
            mockMvc.post("/snippets/$missing/format") {
                with(token)
                contentType = MediaType.APPLICATION_JSON
                content = """{"content": "println(1);"}"""
            },
            mockMvc.post("/snippets/$missing/tests/$missing/run") { with(token) },
        )

    private fun ResultActionsDsl.isForbidden() =
        andExpect {
            status { isForbidden() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")) }
        }

    /** Pasa la seguridad: lo que responda después (un 404 porque el snippet no existe, por ejemplo) ya no importa acá. */
    private fun ResultActionsDsl.isAllowed() {
        val status = andReturn().response.status
        assertTrue(status != 401 && status != 403, "Respondió $status")
    }

    @Test
    fun `con read y write se puede todo`() {
        mockMvc.get("/snippets") { with(asUser("auth0|ana")) }.andExpect { status { isOk() } }
        mockMvc.delete("/snippets/$missing") { with(asUser("auth0|ana")) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `con solo read se lee y se corre, pero no se cambia nada`() {
        reads(readOnly).forEach { it.isAllowed() }
        writes(readOnly).forEach { it.isForbidden() }
    }

    @Test
    fun `un 403 no llega a tocar nada`() {
        writes(readOnly)

        verifyNoInteractions(languageClient)
        verifyNoInteractions(permissionClient)
    }

    @Test
    fun `con solo write no se lee`() {
        reads(writeOnly).forEach { it.isForbidden() }
    }

    @Test
    fun `un token sin scopes no sirve para nada`() {
        reads(noScopes).forEach { it.isForbidden() }
        writes(noScopes).forEach { it.isForbidden() }
    }

    @Test
    fun `buscar usuarios para compartir es leer`() {
        mockMvc.get("/users") { with(writeOnly) }.isForbidden()
    }

    @Test
    fun `sin token sigue siendo 401 y el health check sigue abierto`() {
        mockMvc
            .post("/snippets") {
                contentType = MediaType.APPLICATION_JSON
                content = snippetJson
            }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    /**
     * Con un access token de verdad (no el de [asUser]): Auth0 manda `scope` como un texto separado por
     * espacios, y Spring arma una authority `SCOPE_` por cada uno.
     */
    @Test
    fun `los scopes salen del claim scope del token de Auth0`() {
        Mockito.`when`(jwtDecoder.decode("token-de-lectura")).thenReturn(
            Jwt
                .withTokenValue("token-de-lectura")
                .header("alg", "RS256")
                .subject("auth0|ana")
                .claim("scope", "openid profile read:snippets")
                .build(),
        )

        mockMvc.get("/snippets") { header(HttpHeaders.AUTHORIZATION, "Bearer token-de-lectura") }.andExpect { status { isOk() } }
        mockMvc.delete("/snippets/$missing") { header(HttpHeaders.AUTHORIZATION, "Bearer token-de-lectura") }.isForbidden()
    }
}
