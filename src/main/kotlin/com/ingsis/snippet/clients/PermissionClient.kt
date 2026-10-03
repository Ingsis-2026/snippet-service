package com.ingsis.snippet.clients

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

internal data class PermissionRequest(
    val snippetId: UUID,
    val userId: String,
    val role: String,
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
            .body(PermissionRequest(snippetId, userId, "OWNER"))
            .retrieve()
            .toBodilessEntity()
    }
}
