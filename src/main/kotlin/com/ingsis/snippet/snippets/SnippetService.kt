package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
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

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val languageClient: LanguageClient,
    private val permissionClient: PermissionClient,
    private val access: SnippetAccess,
) {
    /**
     * Solo se guarda un snippet que su parser acepta (US1, US3). El owner se registra en
     * permission-service dentro de la misma transacción: si ese llamado falla, el snippet no
     * queda guardado sin dueño.
     */
    @Transactional
    fun create(
        ownerId: String,
        data: SnippetData,
    ): Snippet {
        validate(data)
        val snippet =
            repository.save(
                Snippet(data.name, data.description, data.language, data.version, data.content, ownerId),
            )
        permissionClient.grantOwner(snippet.id!!, ownerId)
        return snippet
    }

    /** US6: ver un snippet. Alcanza con que se lo hayan compartido. */
    @Transactional(readOnly = true)
    fun get(
        id: UUID,
        userId: String,
    ): Snippet = access.requireReader(id, userId)

    /**
     * Reemplaza todos los datos del snippet (US2, US4). Solo puede hacerlo su owner, y el
     * resultado tiene que cumplir las reglas del lenguaje, que puede haber cambiado.
     *
     * El estado vuelve a `PENDING`: el contenido nuevo todavía no se linteó.
     */
    @Transactional
    fun update(
        id: UUID,
        userId: String,
        data: SnippetData,
    ): Snippet {
        val snippet = access.requireOwner(id, userId)
        validate(data)
        snippet.apply {
            name = data.name
            description = data.description
            language = data.language
            version = data.version
            content = data.content
            status = ValidityStatus.PENDING
        }
        return repository.save(snippet)
    }

    private fun validate(data: SnippetData) {
        val errors = languageClient.validate(data.content, data.language, data.version)
        if (errors.isNotEmpty()) throw InvalidSnippetException(errors)
    }
}

class InvalidSnippetException(
    val errors: List<CodeError>,
) : RuntimeException("El snippet no es válido")
