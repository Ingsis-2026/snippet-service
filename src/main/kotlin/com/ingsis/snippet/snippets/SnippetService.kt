package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.rules.RuleService
import com.ingsis.snippet.rules.RuleType
import com.ingsis.snippet.storage.ContentStore
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Todo lo que el usuario provee de un snippet, tanto al crearlo como al actualizarlo. */
data class SnippetData(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)

/** Un snippet con su código, que se guarda aparte de su metadata. */
data class SnippetWithContent(
    val snippet: Snippet,
    val content: String,
)

/** Se actualizó un snippet. Se publica dentro del update y se atiende cuando quedó guardado (US16). */
data class SnippetUpdatedEvent(
    val snippet: Snippet,
    val content: String,
)

data class SnippetFile(
    val filename: String,
    val content: String,
)

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val languageClient: LanguageClient,
    private val permissionClient: PermissionClient,
    private val contents: ContentStore,
    private val access: SnippetAccess,
    private val ruleService: RuleService,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Solo se guarda un snippet que su parser acepta (US1, US3). El owner se registra en
     * permission-service dentro de la misma transacción: si ese llamado falla, el snippet no
     * queda guardado sin dueño.
     *
     * El código se guarda antes que el owner: si falla guardarlo, no queda un permiso de un
     * snippet que no existe. Si el que falla es el owner, queda un blob que nadie lee.
     */
    @Transactional
    fun create(
        ownerId: String,
        data: SnippetData,
    ): SnippetWithContent {
        validate(data)
        val snippet = repository.save(Snippet(data.name, data.description, data.language, data.version, ownerId))
        contents.save(snippet.id!!, data.content)
        permissionClient.grantOwner(snippet.id!!, ownerId)
        return SnippetWithContent(snippet, data.content)
    }

    /** US6: ver un snippet. Alcanza con que se lo hayan compartido. */
    @Transactional(readOnly = true)
    fun get(
        id: UUID,
        userId: String,
    ): SnippetWithContent = withContent(access.requireReader(id, userId))

    /**
     * US5: los snippets que el usuario puede ver. Cuáles son lo decide permission-service; el
     * filtro, el orden y la página se resuelven en la base, sobre esos ids. El código se lee solo
     * para los snippets de la página.
     */
    @Transactional(readOnly = true)
    fun list(
        userId: String,
        filter: SnippetFilter,
        pageable: Pageable,
    ): Page<SnippetWithContent> {
        SnippetFilter.requireSortable(pageable.sort)
        val ids = permissionClient.snippetsOf(userId, filter.relation.role)
        if (ids.isEmpty()) return Page.empty(pageable)
        return repository.findAll(filter.among(ids), pageable).map { withContent(it) }
    }

    /**
     * US14: las reglas de lint que el snippet no cumple. Son las de su owner, porque las elige
     * quien administra el snippet, y las ve cualquiera que pueda ver el snippet (US6).
     */
    @Transactional(readOnly = true)
    fun lint(
        id: UUID,
        userId: String,
    ): List<CodeError> {
        val snippet = access.requireReader(id, userId)
        val rules = ruleService.configOf(snippet.ownerId, RuleType.LINTING)
        return languageClient.lint(contents.load(id), snippet.language, snippet.version, rules)
    }

    /**
     * US13: el snippet como archivo, tal cual o formateado. Igual que con el lint, se formatea con
     * las reglas de su owner, y lo descarga cualquiera que pueda verlo.
     */
    @Transactional(readOnly = true)
    fun download(
        id: UUID,
        userId: String,
        formatted: Boolean,
    ): SnippetFile {
        val snippet = access.requireReader(id, userId)
        val original = contents.load(id)
        val content =
            if (formatted) {
                val rules = ruleService.configOf(snippet.ownerId, RuleType.FORMATTING)
                languageClient.format(original, snippet.language, snippet.version, rules)
            } else {
                original
            }
        return SnippetFile("${snippet.name}.${extensionOf(snippet.language)}", content)
    }

    /** La extensión que se espera para un lenguaje; texto plano si no se la conoce. */
    private fun extensionOf(language: String) = if (language.equals("printscript", ignoreCase = true)) "ps" else "txt"

    /**
     * US7: solo el owner comparte, y quien lo recibe queda con permiso de lectura sobre el
     * snippet y sus tests. Compartirlo con alguien que ya tiene acceso, incluido el owner, es un
     * conflicto.
     */
    @Transactional(readOnly = true)
    fun share(
        id: UUID,
        ownerId: String,
        userId: String,
    ): SnippetWithContent {
        val snippet = access.requireOwner(id, ownerId)
        permissionClient.grantRead(id, userId)
        return withContent(snippet)
    }

    /**
     * Reemplaza todos los datos del snippet (US2, US4). Solo puede hacerlo su owner, y el
     * resultado tiene que cumplir las reglas del lenguaje, que puede haber cambiado.
     *
     * El estado vuelve a `PENDING`: el contenido nuevo todavía no se linteó. Una vez guardado, se
     * corren todos sus tests (US16).
     */
    @Transactional
    fun update(
        id: UUID,
        userId: String,
        data: SnippetData,
    ): SnippetWithContent {
        val snippet = access.requireOwner(id, userId)
        validate(data)
        snippet.apply {
            name = data.name
            description = data.description
            language = data.language
            version = data.version
            status = ValidityStatus.PENDING
        }
        val saved = repository.save(snippet)
        contents.save(id, data.content)
        events.publishEvent(SnippetUpdatedEvent(saved, data.content))
        return SnippetWithContent(saved, data.content)
    }

    private fun withContent(snippet: Snippet) = SnippetWithContent(snippet, contents.load(snippet.id!!))

    private fun validate(data: SnippetData) {
        val errors = languageClient.validate(data.content, data.language, data.version)
        if (errors.isNotEmpty()) throw InvalidSnippetException(errors)
    }
}

class InvalidSnippetException(
    val errors: List<CodeError>,
) : RuntimeException("El snippet no es válido")
