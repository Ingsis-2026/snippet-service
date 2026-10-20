package com.ingsis.snippet

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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

/** US6: ver un snippet. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ViewSnippetTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private val code = "let x: number = 5;\nprintln(x);"
    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = repository.save(Snippet("Saludo", "Imprime un número", "printscript", "1.0", code, "auth0|ana"))
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|beto")).thenReturn(Role.READ)
    }

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun view(
        user: String,
        id: UUID = snippet.id!!,
    ) = mockMvc.get("/snippets/$id") { header(USER_HEADER, user) }

    @Test
    fun `el owner ve nombre, descripcion, lenguaje y contenido`() {
        view("auth0|ana").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(snippet.id.toString()) }
            jsonPath("$.name") { value("Saludo") }
            jsonPath("$.description") { value("Imprime un número") }
            jsonPath("$.language") { value("printscript") }
            jsonPath("$.version") { value("1.0") }
            jsonPath("$.content") { value(code) }
        }
    }

    @Test
    fun `alguien con quien se compartio el snippet lo ve`() {
        view("auth0|beto").andExpect {
            status { isOk() }
            jsonPath("$.content") { value(code) }
        }
    }

    @Test
    fun `alguien sin permisos no lo ve`() {
        view("auth0|carla").andExpect {
            status { isForbidden() }
            jsonPath("$.content") { doesNotExist() }
        }
    }

    @Test
    fun `un snippet que no existe es un 404`() {
        view("auth0|ana", id = UUID.randomUUID()).andExpect { status { isNotFound() } }
    }
}
