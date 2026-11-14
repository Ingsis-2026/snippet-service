package com.ingsis.snippet.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.util.UUID

/**
 * Dónde vive el código de cada snippet. En Postgres queda solo su metadata.
 *
 * Cada snippet tiene dos versiones de su código:
 * - El **actual**, que es el que se lintea, se ejecuta, se testea y se muestra. El formateo
 *   automático (US12) lo reemplaza por el código formateado.
 * - El **original**: el último que escribió el usuario, al crearlo o actualizarlo. El formateo
 *   automático no lo toca, así se lo puede seguir descargando tal cual (US13).
 */
interface ContentStore {
    /** Guarda el código actual del snippet, reemplazando el anterior si lo había. */
    fun save(
        id: UUID,
        content: String,
    )

    fun load(id: UUID): String

    /** Guarda el código como lo escribió el usuario, reemplazando el anterior si lo había. */
    fun saveOriginal(
        id: UUID,
        content: String,
    )

    /**
     * El código como lo escribió el usuario, o `null` si no está: los snippets creados antes de
     * que se guardara aparte solo tienen el actual.
     */
    fun loadOriginal(id: UUID): String?

    /** Borra las dos versiones del código del snippet. Si ya no estaban, no hay nada que hacer. */
    fun delete(id: UUID)
}

/**
 * El código como assets del asset-service de la cátedra, en el mismo container: el actual se llama
 * como el id del snippet y el original como el id seguido de `-original`. Sin puntos ni barras, para
 * que asset-service no lo tome como una extensión o como otro segmento del path.
 *
 * asset-service es el único que habla con Azurite, y crea el container si todavía no existe en cada
 * pedido: acá no hace falta crearlo.
 */
@Component
class AssetServiceContentStore(
    builder: RestClient.Builder,
    @Value("\${services.asset-url}") baseUrl: String,
    @Value("\${storage.container}") private val container: String,
) : ContentStore {
    private val client = builder.baseUrl(baseUrl).build()

    override fun save(
        id: UUID,
        content: String,
    ) = storing(id, "guardar el código") { put(id.toString(), content) }

    override fun saveOriginal(
        id: UUID,
        content: String,
    ) = storing(id, "guardar el código original") { put(originalKey(id), content) }

    override fun load(id: UUID): String = storing(id, "leer el código") { get(id.toString()) }

    /** Un 404 es un snippet de antes de que se guardara el original, no un error. */
    override fun loadOriginal(id: UUID): String? =
        storing(id, "leer el código original") {
            try {
                get(originalKey(id))
            } catch (e: HttpClientErrorException.NotFound) {
                null
            }
        }

    /**
     * Primero el actual: si falla borrar el original, queda un asset que nadie lee. Un snippet de
     * antes de que se guardara el original no lo tiene, y eso también está bien.
     */
    override fun delete(id: UUID) {
        storing(id, "borrar el código") { remove(id.toString()) }
        storing(id, "borrar el código original") { remove(originalKey(id)) }
    }

    /** asset-service responde 201 si el asset es nuevo y 200 si lo reemplazó: los dos están bien. */
    private fun put(
        key: String,
        content: String,
    ) {
        client
            .put()
            .uri(ASSET_PATH, container, key)
            .contentType(TEXT_UTF8)
            .body(content.toByteArray(Charsets.UTF_8))
            .retrieve()
            .toBodilessEntity()
    }

    /**
     * Se lee en bytes y se decodifica en UTF-8 a mano: asset-service no dice en qué charset viene,
     * y el código puede tener tildes o eñes.
     */
    private fun get(key: String): String {
        val bytes =
            client
                .get()
                .uri(ASSET_PATH, container, key)
                .retrieve()
                .body<ByteArray>()
        // Un asset vacío llega sin cuerpo.
        return bytes?.toString(Charsets.UTF_8) ?: ""
    }

    /** asset-service responde 204 si lo borró y 404 si no existía: los dos lo dejan borrado. */
    private fun remove(key: String) {
        try {
            client
                .delete()
                .uri(ASSET_PATH, container, key)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException.NotFound) {
            // Ya estaba borrado.
        }
    }

    private fun <T> storing(
        id: UUID,
        action: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: RestClientException) {
            throw StorageException("No se pudo $action del snippet $id", e)
        }

    private companion object {
        const val ASSET_PATH = "/v1/asset/{container}/{key}"
        val TEXT_UTF8 = MediaType("text", "plain", Charsets.UTF_8)

        fun originalKey(id: UUID) = "$id-original"
    }
}

class StorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
