package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Los usuarios no pueden ser anónimos: cada pedido trae un access token de Auth0. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @Test
    fun `sin token es un 401 que pide un Bearer`() {
        mockMvc.get("/snippets").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")) }
        }
        verifyNoInteractions(permissionClient)
    }

    @Test
    fun `el header X-User-Id ya no identifica a nadie`() {
        mockMvc.get("/snippets") { header("X-User-Id", "auth0|ana") }.andExpect { status { isUnauthorized() } }
        verifyNoInteractions(permissionClient)
    }

    @Test
    fun `un token que no es Bearer no sirve`() {
        mockMvc.get("/snippets") { header(HttpHeaders.AUTHORIZATION, "Basic YW5hOjEyMzQ=") }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `el usuario es el sub del token`() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", null)).thenReturn(emptyList())

        mockMvc.get("/snippets") { with(asUser("auth0|ana")) }.andExpect { status { isOk() } }

        verify(permissionClient).snippetsOf("auth0|ana", null)
    }

    @Test
    fun `el health check no pide token, para Docker y Swarm`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
