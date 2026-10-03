package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.snippets.ValidityStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

/** US2 (actualizar por archivo) y US4 (actualizar desde el editor). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpdateSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val original = "let x: number = 5;\nprintln(x);"
    private val updated = "let y: number = 7;\nprintln(y);"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet =
            repository.save(
                Snippet("Saludo", "Imprime un número", "printscript", "1.0", original, "auth0|ana", ValidityStatus.COMPLIANT),
            )
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
    }

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun stored() = repository.findById(snippet.id!!).get()

    private fun uploadUpdate(
        id: UUID = snippet.id!!,
        user: String = "auth0|ana",
        content: String = updated,
    ) = mockMvc.multipart(HttpMethod.PUT, "/snippets/$id") {
        file(MockMultipartFile("file", "saludo.ps", "text/plain", content.toByteArray()))
        param("name", "Saludo v2")
        param("description", "Imprime otro número")
        param("language", "printscript")
        param("version", "1.1")
        header(USER_HEADER, user)
    }

    private fun editorUpdate(
        user: String = "auth0|ana",
        content: String = updated,
    ) = mockMvc.put("/snippets/${snippet.id}") {
        header(USER_HEADER, user)
        contentType = MediaType.APPLICATION_JSON
        this.content =
            """{"name": "Saludo v2", "description": "Imprime otro número", "language": "printscript", "version": "1.1",
               "content": ${quote(content)}}"""
    }

    @Test
    fun `el owner actualiza el snippet subiendo un archivo`() {
        uploadUpdate().andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Saludo v2") }
            jsonPath("$.description") { value("Imprime otro número") }
            jsonPath("$.version") { value("1.1") }
            jsonPath("$.content") { value(updated) }
            jsonPath("$.ownerId") { value("auth0|ana") }
        }

        val saved = stored()
        assertEquals("Saludo v2", saved.name)
        assertEquals(updated, saved.content)
        assertEquals("1.1", saved.version)
    }

    @Test
    fun `el owner actualiza el snippet desde el editor`() {
        editorUpdate().andExpect {
            status { isOk() }
            jsonPath("$.content") { value(updated) }
        }
        assertEquals(updated, stored().content)
    }

    @Test
    fun `actualizar vuelve el estado a PENDING porque el contenido nuevo no se linteo`() {
        editorUpdate().andExpect { jsonPath("$.status") { value("PENDING") } }
        assertEquals(ValidityStatus.PENDING, stored().status)
    }

    @Test
    fun `una actualizacion invalida no cambia nada e informa regla, linea y columna`() {
        Mockito
            .`when`(languageClient.validate(updated, "printscript", "1.1"))
            .thenReturn(listOf(CodeError("Carácter inválido encontrado: '@'", 2, 3)))

        uploadUpdate().andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].rule") { value("Carácter inválido encontrado: '@'") }
            jsonPath("$.errors[0].line") { value(2) }
            jsonPath("$.errors[0].column") { value(3) }
        }

        val saved = stored()
        assertEquals("Saludo", saved.name)
        assertEquals(original, saved.content)
        assertEquals(ValidityStatus.COMPLIANT, saved.status)
    }

    @Test
    fun `alguien con permiso de lectura no puede actualizar`() {
        editorUpdate(user = "auth0|beto").andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Solo el owner puede modificar el snippet") }
        }
        assertEquals(original, stored().content)
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `alguien sin permisos no puede actualizar`() {
        uploadUpdate(user = "auth0|carla").andExpect { status { isForbidden() } }
        assertEquals(original, stored().content)
    }

    @Test
    fun `actualizar un snippet que no existe es un 404`() {
        uploadUpdate(id = UUID.randomUUID()).andExpect { status { isNotFound() } }
        verifyNoInteractions(languageClient)
    }

    @Test
    fun `actualizar con un archivo vacio es un 400`() {
        uploadUpdate(content = "").andExpect { status { isBadRequest() } }
        assertEquals(original, stored().content)
    }
}
