package com.ingsis.snippet.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.util.UUID

/** Dónde vive el código de cada snippet. En Postgres queda solo su metadata. */
interface ContentStore {
    /** Guarda el código del snippet, reemplazando el anterior si lo había. */
    fun save(
        id: UUID,
        content: String,
    )

    fun load(id: UUID): String

    /** Borra el código del snippet. Si ya no estaba, no hay nada que hacer. */
    fun delete(id: UUID)
}

/**
 * El código como un asset por snippet, que se llama como su id, en el asset-service de la cátedra.
 * Él es el único que habla con Azurite, y crea el container si todavía no existe en cada pedido:
 * acá no hace falta crearlo.
 */
@Component
class AssetServiceContentStore(
    builder: RestClient.Builder,
    @Value("\${services.asset-url}") baseUrl: String,
    @Value("\${storage.container}") private val container: String,
) : ContentStore {
    private val client = builder.baseUrl(baseUrl).build()

    /** asset-service responde 201 si el asset es nuevo y 200 si lo reemplazó: los dos están bien. */
    override fun save(
        id: UUID,
        content: String,
    ) = storing(id, "guardar") {
        client
            .put()
            .uri(ASSET_PATH, container, id)
            .contentType(TEXT_UTF8)
            .body(content.toByteArray(Charsets.UTF_8))
            .retrieve()
            .toBodilessEntity()
        Unit
    }

    /**
     * Se lee en bytes y se decodifica en UTF-8 a mano: asset-service no dice en qué charset viene,
     * y el código puede tener tildes o eñes.
     */
    override fun load(id: UUID): String =
        storing(id, "leer") {
            val bytes =
                client
                    .get()
                    .uri(ASSET_PATH, container, id)
                    .retrieve()
                    .body<ByteArray>()
            // Un asset vacío llega sin cuerpo.
            bytes?.toString(Charsets.UTF_8) ?: ""
        }

    /** asset-service responde 204 si lo borró y 404 si no existía: los dos dejan el código borrado. */
    override fun delete(id: UUID) =
        storing(id, "borrar") {
            try {
                client
                    .delete()
                    .uri(ASSET_PATH, container, id)
                    .retrieve()
                    .toBodilessEntity()
            } catch (e: HttpClientErrorException.NotFound) {
                // Ya estaba borrado.
            }
            Unit
        }

    private fun <T> storing(
        id: UUID,
        action: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: RestClientException) {
            throw StorageException("No se pudo $action el código del snippet $id", e)
        }

    private companion object {
        const val ASSET_PATH = "/v1/asset/{container}/{key}"
        val TEXT_UTF8 = MediaType("text", "plain", Charsets.UTF_8)
    }
}

class StorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
