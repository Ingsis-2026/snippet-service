package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.rules.RuleService
import com.ingsis.snippet.rules.RuleType
import com.ingsis.snippet.storage.ContentStore
import com.ingsis.snippet.storage.StorageException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
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

/** Se creó un snippet. Se publica dentro de la creación y se atiende cuando quedó guardado. */
data class SnippetCreatedEvent(
    val snippet: Snippet,
)

/** Se actualizó un snippet. Se publica dentro del update y se atiende cuando quedó guardado (US16). */
data class SnippetUpdatedEvent(
    val snippet: Snippet,
    val content: String,
)

/**
 * Se está borrando un snippet. Se publica dentro del borrado, antes de borrar el snippet, para que
 * lo que depende de él (sus tests) se borre en la misma transacción.
 */
data class SnippetDeletedEvent(
    val snippetId: UUID,
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
     *
     * Arranca `PENDING`: se lintea aparte, una vez guardado.
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
        events.publishEvent(SnippetCreatedEvent(snippet))
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
    ): List<CodeError> = errorsOf(access.requireReader(id, userId))

    /** Las reglas de lint de su owner que el snippet no cumple, con su código tal como está ahora. */
    fun errorsOf(snippet: Snippet): List<CodeError> {
        val rules = ruleService.configOf(snippet.ownerId, RuleType.LINTING)
        return languageClient.lint(contents.load(snippet.id!!), snippet.language, snippet.version, rules)
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
        val content = if (formatted) formatted(snippet, original) else original
        return SnippetFile("${snippet.name}.${extensionOf(snippet.language)}", content)
    }

    /** [code] con las reglas de formato del owner de [snippet] aplicadas (US12, US13). */
    fun formatted(
        snippet: Snippet,
        code: String,
    ): String {
        val rules = ruleService.configOf(snippet.ownerId, RuleType.FORMATTING)
        return languageClient.format(code, snippet.language, snippet.version, rules)
    }

    /**
     * Formatear el código del editor sin guardarlo, para verlo antes de decidir si se guarda. Es
     * el código que llega y no el guardado, pero con el lenguaje y la versión del snippet y las
     * reglas de su owner, como al descargarlo formateado (US13). Alcanza con poder verlo.
     */
    @Transactional(readOnly = true)
    fun format(
        id: UUID,
        userId: String,
        code: String,
    ): String = formatted(access.requireReader(id, userId), code)

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
     * lintea aparte y se corren todos sus tests (US16).
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

    /**
     * Solo el owner borra un snippet, y con él se van sus tests, sus permisos y su código.
     *
     * Los tests y el snippet se borran en la base antes de llamar a permission-service: si ese
     * llamado falla, se deshace todo y el snippet queda como estaba. En el orden inverso, si
     * fallara la base quedaría un snippet sin permisos, que nadie vería, ni su owner para volver
     * a borrarlo.
     *
     * El código se borra recién cuando quedó todo borrado, y si falla solo se loguea: queda un
     * asset que nadie lee, pero el snippet ya no existe.
     */
    @Transactional
    fun delete(
        id: UUID,
        userId: String,
    ) {
        access.requireOwner(id, userId)
        events.publishEvent(SnippetDeletedEvent(id))
        repository.deleteById(id)
        repository.flush()
        permissionClient.revokeAll(id)
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun deleteContent(event: SnippetDeletedEvent) {
        try {
            contents.delete(event.snippetId)
        } catch (e: StorageException) {
            log.warn("Se borró el snippet ${event.snippetId} pero no su código", e)
        }
    }

    private fun withContent(snippet: Snippet) = SnippetWithContent(snippet, contents.load(snippet.id!!))

    private fun validate(data: SnippetData) {
        val errors = languageClient.validate(data.content, data.language, data.version)
        if (errors.isNotEmpty()) throw InvalidSnippetException(errors)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(SnippetService::class.java)
    }
}

class InvalidSnippetException(
    val errors: List<CodeError>,
) : RuntimeException("El snippet no es válido")
