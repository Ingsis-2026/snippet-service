package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.USER_HEADER
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.storage.ContentStore
import org.hamcrest.Matchers.contains
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

/** US5: ver todos los snippets a los que tengo acceso. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ListSnippetsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val contents: ContentStore,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private lateinit var fizz: Snippet
    private lateinit var buzz: Snippet
    private lateinit var adder: Snippet

    @BeforeEach
    fun setUp() {
        // Ana es dueña de Fizz y Buzz, y Beto le compartió Adder. Carla tiene uno que Ana no ve.
        fizz = save("Fizz", "printscript", "auth0|ana", ValidityStatus.COMPLIANT)
        buzz = save("Buzz", "python", "auth0|ana", ValidityStatus.NOT_COMPLIANT)
        adder = save("Adder", "printscript", "auth0|beto", ValidityStatus.PENDING)
        save("Secreto", "printscript", "auth0|carla", ValidityStatus.COMPLIANT)

        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", null)).thenReturn(listOf(fizz.id!!, buzz.id!!, adder.id!!))
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", Role.OWNER)).thenReturn(listOf(fizz.id!!, buzz.id!!))
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", Role.READ)).thenReturn(listOf(adder.id!!))
    }

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun save(
        name: String,
        language: String,
        owner: String,
        status: ValidityStatus,
    ) = repository.save(Snippet(name, "Un snippet", language, "1.0", owner, status), "println(1);", contents)

    private fun list(
        vararg params: Pair<String, String>,
        user: String = "auth0|ana",
    ) = mockMvc.get("/snippets") {
        header(USER_HEADER, user)
        params.forEach { (key, value) -> param(key, value) }
    }

    @Test
    fun `por defecto lista los propios y los compartidos, ordenados por nombre`() {
        list().andExpect {
            status { isOk() }
            jsonPath("$.content[*].name") { value(contains("Adder", "Buzz", "Fizz")) }
            jsonPath("$.content[0].status") { value("PENDING") }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(10) }
            jsonPath("$.total") { value(3) }
        }
    }

    @Test
    fun `se pueden pedir solo los propios`() {
        list("relation" to "OWNER").andExpect {
            jsonPath("$.content[*].name") { value(contains("Buzz", "Fizz")) }
        }
    }

    @Test
    fun `se pueden pedir solo los compartidos`() {
        list("relation" to "SHARED").andExpect {
            jsonPath("$.content[*].name") { value(contains("Adder")) }
            jsonPath("$.content[0].ownerId") { value("auth0|beto") }
        }
    }

    @Test
    fun `filtra por parte del nombre sin distinguir mayusculas`() {
        list("name" to "zz").andExpect {
            jsonPath("$.content[*].name") { value(contains("Buzz", "Fizz")) }
        }
        list("name" to "ADD").andExpect {
            jsonPath("$.content[*].name") { value(contains("Adder")) }
        }
    }

    @Test
    fun `filtra por lenguaje`() {
        list("language" to "PrintScript").andExpect {
            jsonPath("$.content[*].name") { value(contains("Adder", "Fizz")) }
        }
    }

    @Test
    fun `filtra por validez`() {
        list("status" to "NOT_COMPLIANT").andExpect {
            jsonPath("$.content[*].name") { value(contains("Buzz")) }
        }
    }

    @Test
    fun `combina los filtros`() {
        list("relation" to "OWNER", "language" to "printscript", "status" to "COMPLIANT").andExpect {
            jsonPath("$.content[*].name") { value(contains("Fizz")) }
            jsonPath("$.total") { value(1) }
        }
    }

    @Test
    fun `ordena por validez y por lenguaje`() {
        list("sort" to "status,desc").andExpect {
            jsonPath("$.content[*].status") { value(contains("PENDING", "NOT_COMPLIANT", "COMPLIANT")) }
        }
        list("sort" to "language,desc").andExpect {
            jsonPath("$.content[0].name") { value("Buzz") }
        }
    }

    @Test
    fun `pagina los resultados`() {
        list("size" to "2", "page" to "1").andExpect {
            jsonPath("$.content[*].name") { value(contains("Fizz")) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.total") { value(3) }
        }
    }

    @Test
    fun `sin permisos sobre ningun snippet la lista esta vacia`() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|dario", null)).thenReturn(emptyList())

        list(user = "auth0|dario").andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
            jsonPath("$.total") { value(0) }
        }
    }

    @Test
    fun `ordenar por un campo que no se puede es un 400`() {
        list("sort" to "content").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("No se puede ordenar por content; se puede por name, language, status") }
        }
    }

    @Test
    fun `una relacion desconocida es un 400`() {
        list("relation" to "FRIENDS").andExpect { status { isBadRequest() } }
    }
}
