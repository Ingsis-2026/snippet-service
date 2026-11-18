package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.linting.StuckLintSweeper
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.put
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Los snippets que quedaron `PENDING` sin un mensaje que los vaya a lintear se vuelven a encolar. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StuckLintSweepTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val sweeper: StuckLintSweeper,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val contents: ContentStore,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @MockitoSpyBean
    private lateinit var queue: RecordingLintQueue

    private val longAgo = Instant.now() - Duration.ofHours(1)

    @BeforeEach
    fun setUp() {
        queue.clear()
    }

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        queue.clear()
    }

    private fun save(
        name: String,
        status: ValidityStatus,
        since: Instant?,
        owner: String = "auth0|ana",
    ) = snippets.save(Snippet(name, "d", "printscript", "1.1", owner, status, since), "println(1);", contents)

    @Test
    fun `encola los que llevan mucho pendientes y solo esos`() {
        val stuck = save("Trabado", ValidityStatus.PENDING, longAgo)
        save("Recién", ValidityStatus.PENDING, Instant.now())
        save("Cumple", ValidityStatus.COMPLIANT, longAgo)
        save("Con error", ValidityStatus.ERROR, longAgo)

        sweeper.sweep()

        assertEquals(listOf(stuck.id), queue.queued())
    }

    @Test
    fun `encola los pendientes de antes de que se guardara desde cuando`() {
        val old = save("Viejo", ValidityStatus.PENDING, null)

        sweeper.sweep()

        assertEquals(listOf(old.id), queue.queued())
    }

    @Test
    fun `no vuelve a encolar en la proxima vuelta lo que acaba de encolar`() {
        save("Trabado", ValidityStatus.PENDING, longAgo)
        sweeper.sweep()
        queue.clear()

        sweeper.sweep()

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `si no puede encolar no reinicia el tiempo, asi la proxima vuelta lo intenta de nuevo`() {
        val stuck = save("Trabado", ValidityStatus.PENDING, longAgo)
        Mockito.doThrow(QueryTimeoutException("Redis no responde")).`when`(queue).enqueue(Mockito.anyCollection())

        sweeper.sweep()

        Mockito.reset(queue)
        sweeper.sweep()
        assertEquals(listOf(stuck.id), queue.queued())
    }

    @Test
    fun `lintear, cambiar las reglas o actualizar el snippet reinician el tiempo que lleva pendiente`() {
        val linted = save("Linteado", ValidityStatus.PENDING, longAgo)
        val betos = save("De Beto", ValidityStatus.PENDING, longAgo, owner = "auth0|beto")
        val updated = save("Actualizado", ValidityStatus.PENDING, longAgo)
        Mockito.`when`(permissionClient.roleOf(updated.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        Mockito.`when`(languageClient.validate("println(2);", "printscript", "1.1")).thenReturn(emptyList())

        snippets.updateStatus(linted.id!!, ValidityStatus.PENDING)
        snippets.markPendingByOwner(betos.ownerId)
        mockMvc
            .put("/snippets/${updated.id}") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"name": "Actualizado", "description": "d", "language": "printscript", "version": "1.1",
                       "content": "println(2);"}"""
            }.andExpect { status { isOk() } }
        queue.clear()

        sweeper.sweep()

        assertTrue(queue.queued().isEmpty())
    }

    @Test
    fun `una version nueva del parser tambien reinicia el tiempo`() {
        save("Trabado", ValidityStatus.PENDING, longAgo)

        snippets.markAllPending()
        sweeper.sweep()

        assertTrue(queue.queued().isEmpty())
    }
}
