package com.ingsis.snippet

import com.ingsis.snippet.clients.AlreadyHasAccessException
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

/** US7: compartir un snippet. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = repository.save(Snippet("Saludo", "Imprime un número", "printscript", "1.0", "println(5);", "auth0|ana"))
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
    }

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun share(
        with: String,
        user: String = "auth0|ana",
        id: UUID = snippet.id!!,
    ) = mockMvc.post("/snippets/$id/share") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        content = """{"userId": "$with"}"""
    }

    @Test
    fun `el owner comparte el snippet y el otro usuario queda con permiso de lectura`() {
        share("auth0|carla").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(snippet.id.toString()) }
            jsonPath("$.ownerId") { value("auth0|ana") }
        }
        verify(permissionClient).grantRead(snippet.id!!, "auth0|carla")
    }

    @Test
    fun `alguien con permiso de lectura no puede compartirlo`() {
        share("auth0|carla", user = "auth0|beto").andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Solo el owner puede modificar el snippet") }
        }
        verify(permissionClient, never()).grantRead(snippet.id!!, "auth0|carla")
    }

    @Test
    fun `compartirlo con alguien que ya tiene acceso es un 409`() {
        Mockito
            .doThrow(AlreadyHasAccessException("auth0|beto"))
            .`when`(permissionClient)
            .grantRead(snippet.id!!, "auth0|beto")

        share("auth0|beto").andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("El usuario auth0|beto ya tiene acceso a este snippet") }
        }
    }

    @Test
    fun `compartir un snippet que no existe es un 404`() {
        share("auth0|carla", id = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `sin usuario con quien compartir es un 400`() {
        share("").andExpect { status { isBadRequest() } }
    }
}
