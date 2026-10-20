package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.storage.StorageException
import com.ingsis.snippet.tests.TestCase
import com.ingsis.snippet.tests.TestCaseRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.web.client.HttpServerErrorException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Borrar un snippet desde la UI: se van sus tests, sus permisos y su código. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeleteSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val tests: TestCaseRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoSpyBean
    private lateinit var contents: InMemoryContentStore

    private val code = "println(\"Hola\");"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = snippets.save(Snippet("Saludo", "Saluda", "printscript", "1.1", "auth0|ana"), code, contents)
        tests.save(TestCase(snippet, "Saluda", mutableListOf(), mutableListOf("Hola")))
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
    }

    @AfterEach
    fun cleanUp() {
        tests.deleteAll()
        snippets.deleteAll()
    }

    private fun delete(
        user: String = "auth0|ana",
        id: UUID = snippet.id!!,
    ) = mockMvc.delete("/snippets/$id") { header(USER_HEADER, user) }

    @Test
    fun `el owner borra el snippet con sus tests, sus permisos y su codigo`() {
        val other = snippets.save(Snippet("Otro", "d", "printscript", "1.1", "auth0|ana"), "println(1);", contents)
        tests.save(TestCase(other, "Otro", mutableListOf(), mutableListOf("1")))

        delete().andExpect { status { isNoContent() } }

        assertEquals(listOf(other.id), snippets.findAll().map { it.id })
        assertEquals(listOf("Otro"), tests.findAll().map { it.name })
        verify(permissionClient).revokeAll(snippet.id!!)
        assertThrows<StorageException> { contents.load(snippet.id!!) }
        assertEquals("println(1);", contents.load(other.id!!))
    }

    @Test
    fun `alguien con permiso de lectura no puede borrarlo`() {
        delete(user = "auth0|beto").andExpect { status { isForbidden() } }
        assertTrue(snippets.existsById(snippet.id!!))
        verify(permissionClient, never()).revokeAll(snippet.id!!)
    }

    @Test
    fun `alguien sin permisos no puede borrarlo`() {
        delete(user = "auth0|carla").andExpect { status { isForbidden() } }
        assertTrue(snippets.existsById(snippet.id!!))
    }

    @Test
    fun `borrar un snippet que no existe es un 404`() {
        delete(id = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }

    @Test
    fun `si falla permission-service el snippet queda como estaba`() {
        Mockito.doThrow(HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)).`when`(permissionClient).revokeAll(snippet.id!!)

        delete().andExpect { status { isBadGateway() } }

        assertTrue(snippets.existsById(snippet.id!!))
        assertEquals(1, tests.count())
        assertEquals(code, contents.load(snippet.id!!))
    }

    @Test
    fun `si falla borrar el codigo el snippet se borra igual`() {
        Mockito
            .doThrow(StorageException("No se pudo borrar el código del snippet ${snippet.id}", RuntimeException()))
            .`when`(contents)
            .delete(snippet.id!!)

        delete().andExpect { status { isNoContent() } }

        assertEquals(0, snippets.count())
        assertEquals(0, tests.count())
    }
}
