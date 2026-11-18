package com.ingsis.snippet

import com.ingsis.snippet.storage.AssetServiceContentStore
import com.ingsis.snippet.storage.StorageException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * El código en asset-service: el actual en un asset que se llama como el id del snippet, y el original
 * en otro que se llama como el id seguido de `-original`.
 */
class AssetServiceContentStoreTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val store = AssetServiceContentStore(builder, "http://asset", "snippets")
    private val id = UUID.randomUUID()

    @Test
    fun `guarda el codigo en el asset del snippet`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andExpect(content().bytes("println(\"señor\");".toByteArray(Charsets.UTF_8)))
            .andRespond(withStatus(HttpStatus.CREATED))

        store.save(id, "println(\"señor\");")

        server.verify()
    }

    @Test
    fun `reemplazar el codigo de un snippet que ya tenia tambien esta bien`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.OK))

        store.save(id, "println(2);")

        server.verify()
    }

    @Test
    fun `lee el codigo del asset del snippet en UTF-8`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("println(\"señor\");".toByteArray(Charsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM))

        assertEquals("println(\"señor\");", store.load(id))
    }

    @Test
    fun `un asset vacio es codigo vacio`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.OK))

        assertEquals("", store.load(id))
    }

    @Test
    fun `si el codigo no esta dice de que snippet era`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        val error = assertFailsWith<StorageException> { store.load(id) }
        assertEquals("No se pudo leer el código del snippet $id", error.message)
    }

    @Test
    fun `borra el asset del snippet y el de su original`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        store.delete(id)

        server.verify()
    }

    @Test
    fun `borrar un codigo que ya no estaba tambien esta bien`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo("http://asset/v1/asset/snippets/$id-original")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        store.delete(id)

        server.verify()
    }

    @Test
    fun `si asset-service falla al borrar el original dice de que snippet era`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.NO_CONTENT))
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val error = assertFailsWith<StorageException> { store.delete(id) }
        assertEquals("No se pudo borrar el código original del snippet $id", error.message)
    }

    @Test
    fun `guarda el original en su propio asset`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andExpect(content().bytes("let x:number=5;".toByteArray(Charsets.UTF_8)))
            .andRespond(withStatus(HttpStatus.CREATED))

        store.saveOriginal(id, "let x:number=5;")

        server.verify()
    }

    @Test
    fun `lee el original de su propio asset`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("println(\"señor\");".toByteArray(Charsets.UTF_8), MediaType.APPLICATION_OCTET_STREAM))

        assertEquals("println(\"señor\");", store.loadOriginal(id))
    }

    @Test
    fun `un snippet sin original no es un error`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id-original")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertNull(store.loadOriginal(id))
    }

    @Test
    fun `si asset-service falla al leer el original dice de que snippet era`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val error = assertFailsWith<StorageException> { store.loadOriginal(id) }
        assertEquals("No se pudo leer el código original del snippet $id", error.message)
    }

    @Test
    fun `si asset-service falla al guardar el original dice de que snippet era`() {
        server
            .expect(requestTo("http://asset/v1/asset/snippets/$id-original"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val error = assertFailsWith<StorageException> { store.saveOriginal(id, "println(1);") }
        assertEquals("No se pudo guardar el código original del snippet $id", error.message)
    }

    @Test
    fun `si asset-service falla al borrar dice de que snippet era el codigo`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val error = assertFailsWith<StorageException> { store.delete(id) }
        assertEquals("No se pudo borrar el código del snippet $id", error.message)
    }

    @Test
    fun `si asset-service falla al guardar dice de que snippet era el codigo`() {
        server.expect(requestTo("http://asset/v1/asset/snippets/$id")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        val error = assertFailsWith<StorageException> { store.save(id, "println(1);") }
        assertEquals("No se pudo guardar el código del snippet $id", error.message)
    }
}
