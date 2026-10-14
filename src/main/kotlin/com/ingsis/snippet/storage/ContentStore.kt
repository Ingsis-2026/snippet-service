package com.ingsis.snippet.storage

import com.azure.core.util.BinaryData
import com.azure.storage.blob.BlobContainerClient
import com.azure.storage.blob.BlobContainerClientBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.UUID

/** Dónde vive el código de cada snippet. En Postgres queda solo su metadata. */
interface ContentStore {
    /** Guarda el código del snippet, reemplazando el anterior si lo había. */
    fun save(
        id: UUID,
        content: String,
    )

    fun load(id: UUID): String
}

/**
 * El código como un blob por snippet, en Azure Blob Storage. En las VMs es Azurite, que corre en
 * el mismo stack sin publicar su puerto: solo snippet-service llega a él.
 */
@Component
class BlobContentStore(
    private val container: BlobContainerClient,
) : ContentStore {
    /**
     * El container se crea con el primer guardado y no al arrancar: así snippet-service levanta
     * aunque Azurite todavía no esté listo. Si falla, se reintenta en el próximo guardado.
     */
    @Volatile
    private var containerCreated = false

    override fun save(
        id: UUID,
        content: String,
    ) = storing(id, "guardar") {
        if (!containerCreated) {
            container.createIfNotExists()
            containerCreated = true
        }
        container.getBlobClient(id.toString()).upload(BinaryData.fromString(content), true)
    }

    override fun load(id: UUID): String =
        storing(id, "leer") {
            container
                .getBlobClient(id.toString())
                .downloadContent()
                .toString()
        }

    private fun <T> storing(
        id: UUID,
        action: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: RuntimeException) {
            throw StorageException("No se pudo $action el código del snippet $id", e)
        }
}

@Configuration
class StorageConfig {
    @Bean
    fun snippetContainer(
        @Value("\${storage.connection-string}") connectionString: String,
        @Value("\${storage.container}") containerName: String,
    ): BlobContainerClient =
        BlobContainerClientBuilder()
            .connectionString(connectionString)
            .containerName(containerName)
            .buildClient()
}

class StorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
