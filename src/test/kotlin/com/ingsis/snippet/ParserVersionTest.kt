package com.ingsis.snippet

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.parser.ParserVersion
import com.ingsis.snippet.parser.ParserVersionRepository
import com.ingsis.snippet.parser.ParserVersionWatcher
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.storage.ContentStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.QueryTimeoutException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.web.client.ResourceAccessException
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Con cada versión nueva del parser del lenguaje, se vuelven a validar todos los snippets. */
@SpringBootTest
@ActiveProfiles("test")
class ParserVersionTest(
    @Autowired private val watcher: ParserVersionWatcher,
    @Autowired private val versions: ParserVersionRepository,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val contents: ContentStore,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoSpyBean
    private lateinit var queue: RecordingLintQueue

    private val stillValid = "println(1);"
    private val noLongerValid = "const x: number = 1;"
    private lateinit var anas: Snippet
    private lateinit var betos: Snippet

    @BeforeEach
    fun setUp() {
        anas = snippets.save(Snippet("De Ana", "d", "printscript", "1.1", "auth0|ana", ValidityStatus.COMPLIANT), stillValid, contents)
        betos =
            snippets.save(
                Snippet("De Beto", "d", "printscript", "1.1", "auth0|beto", ValidityStatus.NOT_COMPLIANT),
                noLongerValid,
                contents,
            )
        queue.clear()
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        versions.deleteAll()
        queue.clear()
    }

    private fun languageServiceRuns(version: String) = Mockito.`when`(languageClient.parserVersion()).thenReturn(version)

    private fun storedVersion() = versions.findById(ParserVersion.ID).getOrNull()?.version

    private fun statuses() = snippets.findAll().associate { it.name to it.status }

    @Test
    fun `la primera vez se validan todos los snippets, porque no se sabe con que parser se validaron`() {
        languageServiceRuns("1.1.0")

        watcher.check()

        assertEquals(mapOf("De Ana" to ValidityStatus.PENDING, "De Beto" to ValidityStatus.PENDING), statuses())
        assertEquals(setOf(anas.id, betos.id), queue.queued().toSet())
        assertEquals("1.1.0", storedVersion())
    }

    @Test
    fun `con la misma version no se hace nada`() {
        versions.save(ParserVersion("1.1.0"))
        languageServiceRuns("1.1.0")

        watcher.check()

        assertEquals(mapOf("De Ana" to ValidityStatus.COMPLIANT, "De Beto" to ValidityStatus.NOT_COMPLIANT), statuses())
        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `con una version nueva se vuelven a validar todos los snippets, de todos los usuarios`() {
        versions.save(ParserVersion("1.1.0"))
        languageServiceRuns("1.2.0")

        watcher.check()

        assertEquals(mapOf("De Ana" to ValidityStatus.PENDING, "De Beto" to ValidityStatus.PENDING), statuses())
        assertEquals(setOf(anas.id, betos.id), queue.queued().toSet())
        assertEquals("1.2.0", storedVersion())
    }

    @Test
    fun `los que el parser nuevo no acepta quedan INVALID y el resto se lintea como siempre`() {
        versions.save(ParserVersion("1.1.0"))
        languageServiceRuns("1.2.0")
        Mockito
            .`when`(languageClient.validate(noLongerValid, "printscript", "1.1"))
            .thenReturn(listOf(CodeError("Unexpected token const", 1, 1)))
        Mockito.`when`(languageClient.validate(stillValid, "printscript", "1.1")).thenReturn(emptyList())
        Mockito.`when`(languageClient.lint(stillValid, "printscript", "1.1", emptyMap())).thenReturn(emptyList())

        watcher.check()
        queue.processAll()

        assertEquals(mapOf("De Ana" to ValidityStatus.COMPLIANT, "De Beto" to ValidityStatus.INVALID), statuses())
    }

    @Test
    fun `si language-service no responde no se toca nada y se reintenta en la proxima vuelta`() {
        versions.save(ParserVersion("1.1.0"))
        Mockito.`when`(languageClient.parserVersion()).thenThrow(ResourceAccessException("Connection refused"))

        watcher.check()

        assertEquals(mapOf("De Ana" to ValidityStatus.COMPLIANT, "De Beto" to ValidityStatus.NOT_COMPLIANT), statuses())
        assertTrue(queue.queued().isEmpty())
        assertEquals("1.1.0", storedVersion())
    }

    @Test
    fun `si no se pueden encolar no se guarda la version nueva, asi la proxima vuelta lo hace de nuevo`() {
        versions.save(ParserVersion("1.1.0"))
        languageServiceRuns("1.2.0")
        Mockito.doThrow(QueryTimeoutException("Redis no responde")).`when`(queue).enqueue(Mockito.anyCollection())

        watcher.check()

        assertEquals("1.1.0", storedVersion())

        Mockito.reset(queue)
        watcher.check()

        assertEquals(setOf(anas.id, betos.id), queue.queued().toSet())
        assertEquals("1.2.0", storedVersion())
    }

    @Test
    fun `la version guardada solo se reemplaza si nadie la cambio antes`() {
        versions.save(ParserVersion("1.2.0"))

        assertEquals(0, versions.replace("1.1.0", "1.3.0"))
        assertEquals("1.2.0", storedVersion())
    }

    @Test
    fun `sin snippets solo se guarda la version`() {
        snippets.deleteAll()
        languageServiceRuns("1.1.0")

        watcher.check()

        assertTrue(queue.queued().isEmpty())
        assertEquals("1.1.0", storedVersion())
        assertNull(snippets.findAll().firstOrNull())
    }
}
