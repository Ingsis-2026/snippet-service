package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.users.UserDirectory
import com.ingsis.snippet.users.UserPage
import com.ingsis.snippet.users.UserSummary
import com.ingsis.snippet.users.UsersUnavailableException
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** US7: elegir con quién compartir de un listado que se filtra mientras se escribe. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsersTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoBean
    private lateinit var directory: UserDirectory

    @Test
    fun `lista los usuarios que coinciden, sin el que pregunta`() {
        Mockito
            .`when`(directory.search("an", "auth0|beto", 0, 5))
            .thenReturn(UserPage(listOf(UserSummary("auth0|ana", "Ana", "ana@mail.com")), 0, 5, 1))

        mockMvc
            .get("/users?name=an&page=0&size=5") { with(asUser("auth0|beto")) }
            .andExpect {
                status { isOk() }
                jsonPath("$.users[0].id") { value("auth0|ana") }
                jsonPath("$.users[0].name") { value("Ana") }
                jsonPath("$.users[0].email") { value("ana@mail.com") }
                jsonPath("$.page") { value(0) }
                jsonPath("$.size") { value(5) }
                jsonPath("$.total") { value(1) }
            }
    }

    @Test
    fun `sin filtro pide todos, de a 10`() {
        Mockito.`when`(directory.search("", "auth0|beto", 0, 10)).thenReturn(UserPage(emptyList(), 0, 10, 0))

        mockMvc.get("/users") { with(asUser("auth0|beto")) }.andExpect {
            status { isOk() }
            jsonPath("$.users.length()") { value(0) }
        }
    }

    @Test
    fun `sin token es un 401`() {
        mockMvc.get("/users?name=an").andExpect { status { isUnauthorized() } }
        verifyNoInteractions(directory)
    }

    @Test
    fun `una pagina o un tamano fuera de rango es un 400`() {
        mockMvc.get("/users?size=51") { with(asUser("auth0|beto")) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/users?size=0") { with(asUser("auth0|beto")) }.andExpect { status { isBadRequest() } }
        mockMvc.get("/users?page=-1") { with(asUser("auth0|beto")) }.andExpect { status { isBadRequest() } }
        verifyNoInteractions(directory)
    }

    @Test
    fun `si no se puede buscar en Auth0 es un 503 que dice por que`() {
        Mockito
            .`when`(directory.search("an", "auth0|beto", 0, 10))
            .thenThrow(UsersUnavailableException("Falta configurar la aplicación de Auth0 para buscar usuarios"))

        mockMvc.get("/users?name=an") { with(asUser("auth0|beto")) }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value("Falta configurar la aplicación de Auth0 para buscar usuarios") }
        }
    }
}
