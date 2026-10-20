package com.ingsis.snippet

import com.azure.core.util.BinaryData
import com.azure.storage.blob.BlobClient
import com.azure.storage.blob.BlobContainerClient
import com.ingsis.snippet.storage.BlobContentStore
import com.ingsis.snippet.storage.StorageConfig
import com.ingsis.snippet.storage.StorageException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** El código en Azurite: un blob por snippet, que se llama como su id. */
class BlobContentStoreTest {
    private val container = mock(BlobContainerClient::class.java)
    private val blob = mock(BlobClient::class.java)
    private val store = BlobContentStore(container)
    private val id = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        Mockito.`when`(container.getBlobClient(id.toString())).thenReturn(blob)
    }

    @Test
    fun `guarda el codigo reemplazando el anterior y crea el container una sola vez`() {
        store.save(id, "println(1);")
        store.save(id, "println(2);")

        val uploaded = ArgumentCaptor.forClass(BinaryData::class.java)
        verify(blob, times(2)).upload(uploaded.capture(), Mockito.eq(true))
        assertEquals(listOf("println(1);", "println(2);"), uploaded.allValues.map { it.toString() })
        verify(container, times(1)).createIfNotExists()
    }

    @Test
    fun `si no pudo crear el container lo reintenta en el proximo guardado`() {
        Mockito
            .`when`(container.createIfNotExists())
            .thenThrow(RuntimeException("Azurite todavía no levantó"))
            .thenReturn(true)

        assertFailsWith<StorageException> { store.save(id, "println(1);") }
        store.save(id, "println(1);")

        verify(container, times(2)).createIfNotExists()
        verify(blob, times(1)).upload(Mockito.any(BinaryData::class.java), Mockito.eq(true))
    }

    @Test
    fun `lee el codigo del blob del snippet`() {
        Mockito.`when`(blob.downloadContent()).thenReturn(BinaryData.fromString("println(1);"))

        assertEquals("println(1);", store.load(id))
    }

    @Test
    fun `si Azurite falla dice de que snippet era el codigo`() {
        Mockito.`when`(blob.downloadContent()).thenThrow(RuntimeException("Connection refused"))

        val error = assertFailsWith<StorageException> { store.load(id) }
        assertEquals("No se pudo leer el código del snippet $id", error.message)
    }

    @Test
    fun `el container sale de la configuracion`() {
        val connectionString =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;" +
                "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;" +
                "BlobEndpoint=http://azurite:10000/devstoreaccount1;"

        val configured = StorageConfig().snippetContainer(connectionString, "snippets")

        assertEquals("http://azurite:10000/devstoreaccount1/snippets", configured.blobContainerUrl)
    }
}
