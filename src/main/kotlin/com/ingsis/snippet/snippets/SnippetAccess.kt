package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.clients.Role
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Busca un snippet verificando qué puede hacer el usuario con él. Quién puede qué lo decide
 * permission-service; acá solo se traduce a "404 si no existe, 403 si no alcanza".
 */
@Component
class SnippetAccess(
    private val repository: SnippetRepository,
    private val permissionClient: PermissionClient,
) {
    /** Para modificar el snippet o lo que le pertenece, como sus tests. */
    fun requireOwner(
        id: UUID,
        userId: String,
    ): Snippet {
        val snippet = find(id)
        if (permissionClient.roleOf(id, userId) != Role.OWNER) throw NotOwnerException()
        return snippet
    }

    /** Para verlo: alcanza con que se lo hayan compartido. */
    fun requireReader(
        id: UUID,
        userId: String,
    ): Snippet {
        val snippet = find(id)
        if (permissionClient.roleOf(id, userId) == null) throw NoAccessException()
        return snippet
    }

    private fun find(id: UUID): Snippet = repository.findByIdOrNull(id) ?: throw SnippetNotFoundException(id)
}

class SnippetNotFoundException(
    id: UUID,
) : RuntimeException("No existe el snippet $id")

class NotOwnerException : RuntimeException("Solo el owner puede modificar el snippet")

class NoAccessException : RuntimeException("No tenés acceso a este snippet")
