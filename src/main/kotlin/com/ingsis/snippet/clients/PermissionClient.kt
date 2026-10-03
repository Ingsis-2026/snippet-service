package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
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
    ) {
        client
            .post()
            .uri("/permissions")
            .contentType(MediaType.APPLICATION_JSON)
            .body(PermissionRequest(snippetId, userId, Role.OWNER))
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
}
