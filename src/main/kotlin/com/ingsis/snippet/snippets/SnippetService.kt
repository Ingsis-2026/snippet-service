package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class NewSnippet(
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
) {
    /**
     * Solo se guarda un snippet que su parser acepta (US1). El owner se registra en
     * permission-service dentro de la misma transacción: si ese llamado falla, el snippet no
     * queda guardado sin dueño.
     */
    @Transactional
    fun create(
        ownerId: String,
        data: NewSnippet,
    ): Snippet {
        val errors = languageClient.validate(data.content, data.language, data.version)
        if (errors.isNotEmpty()) throw InvalidSnippetException(errors)
        val snippet =
            repository.save(
                Snippet(data.name, data.description, data.language, data.version, data.content, ownerId),
            )
        permissionClient.grantOwner(snippet.id!!, ownerId)
        return snippet
    }
}

class InvalidSnippetException(
    val errors: List<CodeError>,
) : RuntimeException("El snippet no es válido")
