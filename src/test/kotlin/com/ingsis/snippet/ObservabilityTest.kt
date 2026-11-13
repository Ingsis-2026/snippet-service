package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import com.ingsis.snippet.observability.ACTIVITY_EVENT
import com.ingsis.snippet.observability.INVENTORY_EVENT
import com.ingsis.snippet.observability.InventoryReporter
import com.ingsis.snippet.observability.NewRelicTelemetry
import com.ingsis.snippet.snippets.HIDDEN_EVENT
import com.ingsis.snippet.snippets.LISTED_ATTRIBUTE
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lo que se le reporta a New Relic además de lo que mide el agente: la actividad, el inventario y los snippets ocultos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObservabilityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val contents: ContentStore,
    @Autowired private val telemetry: RecordingTelemetry,
    @Autowired private val inventory: InventoryReporter,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    private lateinit var snippet: Snippet

    @BeforeEach
    fun setUp() {
        snippet = repository.save(Snippet("Saludo", "Saluda", "printscript", "1.1", "auth0|ana"), "println(1);", contents)
        Mockito.`when`(permissionClient.roleOf(snippet.id!!, "auth0|ana")).thenReturn(Role.OWNER)
        telemetry.clear()
    }

    @AfterEach
    fun cleanUp() = repository.deleteAll()

    private fun activity() = telemetry.events(ACTIVITY_EVENT)

    @Test
    fun `crear un snippet se reporta con su owner, su lenguaje y su version`() {
        mockMvc
            .post("/snippets") {
                with(asUser("auth0|beto"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "Otro", "description": "x", "language": "printscript", "version": "1.0", "content": "println(2);"}"""
            }.andExpect { status { isCreated() } }

        val created = activity().single()
        assertEquals("created", created["action"])
        assertEquals("auth0|beto", created["ownerId"])
        assertEquals("printscript", created["language"])
        assertEquals("1.0", created["version"])
        assertEquals(
            repository
                .findAll()
                .single { it.name == "Otro" }
                .id
                .toString(),
            created["snippetId"],
        )
    }

    @Test
    fun `un snippet invalido no se guarda y no es actividad`() {
        Mockito
            .`when`(languageClient.validate("@", "printscript", "1.0"))
            .thenReturn(
                listOf(
                    com.ingsis.snippet.clients
                        .CodeError("Carácter inválido", 1, 1),
                ),
            )

        mockMvc
            .post("/snippets") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name": "Mal", "description": "x", "language": "printscript", "version": "1.0", "content": "@"}"""
            }.andExpect { status { isUnprocessableContent() } }

        assertTrue(activity().isEmpty())
    }

    @Test
    fun `actualizar, compartir y borrar se reportan con el owner del snippet`() {
        mockMvc
            .put("/snippets/${snippet.id}") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"name": "Saludo", "description": "Saluda", "language": "printscript", "version": "1.1", "content": "println(3);"}"""
            }.andExpect { status { isOk() } }
        mockMvc
            .post("/snippets/${snippet.id}/share") {
                with(asUser("auth0|ana"))
                contentType = MediaType.APPLICATION_JSON
                content = """{"userId": "auth0|carla"}"""
            }.andExpect { status { isOk() } }
        mockMvc.delete("/snippets/${snippet.id}") { with(asUser("auth0|ana")) }.andExpect { status { isNoContent() } }

        assertEquals(listOf("updated", "shared", "deleted"), activity().map { it["action"] })
        assertTrue(activity().all { it["ownerId"] == "auth0|ana" && it["snippetId"] == snippet.id.toString() })
        assertEquals("auth0|carla", activity()[1]["sharedWith"])
    }

    @Test
    fun `el listado anota cuantos snippets encontro`() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", null)).thenReturn(listOf(snippet.id!!))

        mockMvc.get("/snippets") { with(asUser("auth0|ana")) }.andExpect { status { isOk() } }

        assertEquals(listOf<Number>(1L), telemetry.attributes(LISTED_ATTRIBUTE))
        assertTrue(telemetry.events(HIDDEN_EVENT).isEmpty())
    }

    @Test
    fun `si permission-service no devuelve los snippets que el usuario tiene en la base, se avisa`() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", null)).thenReturn(emptyList())

        mockMvc.get("/snippets") { with(asUser("auth0|ana")) }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(0) }
        }

        assertEquals(listOf<Number>(0), telemetry.attributes(LISTED_ATTRIBUTE))
        val hidden = telemetry.events(HIDDEN_EVENT).single()
        assertEquals("auth0|ana", hidden["userId"])
        assertEquals(1L, hidden["owned"])
    }

    @Test
    fun `un usuario sin snippets propios que lista vacio no es un aviso`() {
        Mockito.`when`(permissionClient.snippetsOf("auth0|beto", null)).thenReturn(emptyList())
        Mockito.`when`(permissionClient.snippetsOf("auth0|ana", Role.READ)).thenReturn(emptyList())

        mockMvc.get("/snippets") { with(asUser("auth0|beto")) }.andExpect { status { isOk() } }
        // Ana tiene uno propio, pero pidió solo los compartidos: que no le hayan compartido nada es normal.
        mockMvc
            .get("/snippets") {
                with(asUser("auth0|ana"))
                param("relation", "SHARED")
            }.andExpect { status { isOk() } }

        assertTrue(telemetry.events(HIDDEN_EVENT).isEmpty())
    }

    @Test
    fun `el inventario cuenta los snippets por estado y los usuarios que tienen alguno`() {
        repository.save(Snippet("Otro", "x", "printscript", "1.1", "auth0|ana", ValidityStatus.COMPLIANT))
        repository.save(Snippet("De Beto", "x", "printscript", "1.1", "auth0|beto", ValidityStatus.INVALID))

        inventory.report()

        val report = telemetry.events(INVENTORY_EVENT).single()
        assertEquals(3L, report["total"])
        assertEquals(2L, report["owners"])
        assertEquals(1L, report["pending"])
        assertEquals(1L, report["compliant"])
        assertEquals(0L, report["notCompliant"])
        assertEquals(1L, report["invalid"])
    }

    @Test
    fun `sin el agente de New Relic, reportar no hace nada`() {
        NewRelicTelemetry().recordEvent(ACTIVITY_EVENT, mapOf("action" to "created", "snippetId" to UUID.randomUUID().toString()))
        NewRelicTelemetry().addTransactionAttribute(LISTED_ATTRIBUTE, 1)
    }
}
