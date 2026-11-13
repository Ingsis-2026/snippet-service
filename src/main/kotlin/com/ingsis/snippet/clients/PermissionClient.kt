package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.util.Optional
import java.util.UUID

enum class Role { OWNER, READ }

internal data class PermissionRequest(
    val snippetId: UUID,
    val userId: String,
    val role: Role,
)

internal data class PermissionResponse(
    val snippetId: UUID,
    val userId: String,
    val role: Role,
)

@Component
class PermissionClient(
    builder: RestClient.Builder,
    @Value("\${services.permission-url}") baseUrl: String,
) {
    private val client = builder.baseUrl(baseUrl).build()

    fun grantOwner(
        snippetId: UUID,
        userId: String,
    ) = grant(snippetId, userId, Role.OWNER)

    /** US7: el usuario pasa a poder ver el snippet y sus tests. */
    fun grantRead(
        snippetId: UUID,
        userId: String,
    ) = grant(snippetId, userId, Role.READ)

    private fun grant(
        snippetId: UUID,
        userId: String,
        role: Role,
    ) {
        try {
            client
                .post()
                .uri("/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(PermissionRequest(snippetId, userId, role))
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException.Conflict) {
            // permission-service guarda un solo rol por usuario y snippet.
            throw AlreadyHasAccessException(userId)
        }
    }

    /** Se borró el snippet: nadie, ni su owner, tiene más permisos sobre él. */
    fun revokeAll(snippetId: UUID) {
        client
            .delete()
            .uri { it.path("/permissions").queryParam("snippetId", snippetId).build() }
            .retrieve()
            .toBodilessEntity()
    }

    /** El rol del usuario sobre el snippet, o `null` si no tiene ninguno. */
    fun roleOf(
        snippetId: UUID,
        userId: String,
    ): Role? =
        try {
            client
                .get()
                .uri {
                    it
                        .path("/permissions")
                        .queryParam("snippetId", snippetId)
                        .queryParam("userId", userId)
                        .build()
                }.retrieve()
                .body<PermissionResponse>()!!
                .role
        } catch (e: HttpClientErrorException.NotFound) {
            null
        }

    /** US5: los snippets sobre los que el usuario tiene algún permiso, o solo los de [role]. */
    fun snippetsOf(
        userId: String,
        role: Role?,
    ): List<UUID> =
        client
            .get()
            .uri {
                it
                    .path("/permissions/snippets")
                    .queryParam("userId", userId)
                    .queryParamIfPresent("role", Optional.ofNullable(role))
                    .build()
            }.retrieve()
            .body<List<PermissionResponse>>()!!
            .map { it.snippetId }
}

class AlreadyHasAccessException(
    userId: String,
) : RuntimeException("El usuario $userId ya tiene acceso a este snippet")
