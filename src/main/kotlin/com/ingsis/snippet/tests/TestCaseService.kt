package com.ingsis.snippet.tests

import com.ingsis.snippet.snippets.SnippetAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class TestCaseData(
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
)

data class TestCaseResponse(
    val id: UUID,
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
)

/**
 * Devuelve [TestCaseResponse] y no la entidad: las listas se cargan de forma perezosa y tienen
 * que leerse dentro de la transacción.
 */
@Service
class TestCaseService(
    private val repository: TestCaseRepository,
    private val access: SnippetAccess,
) {
    /**
     * Solo el owner crea tests (US8). No se corre el snippet para verificarlos: un test
     * "inválido", cuyos outputs no son los que el snippet produce, también se guarda.
     */
    @Transactional
    fun create(
        snippetId: UUID,
        userId: String,
        data: TestCaseData,
    ): TestCaseResponse {
        val snippet = access.requireOwner(snippetId, userId)
        val test = TestCase(snippet, data.name, data.inputs.toMutableList(), data.outputs.toMutableList())
        return repository.save(test).toResponse()
    }

    /** Los tests los ve cualquiera que pueda ver el snippet, incluido alguien a quien se lo compartieron (US7). */
    @Transactional(readOnly = true)
    fun list(
        snippetId: UUID,
        userId: String,
    ): List<TestCaseResponse> {
        access.requireReader(snippetId, userId)
        return repository.findAllBySnippetIdOrderByName(snippetId).map { it.toResponse() }
    }

    private fun TestCase.toResponse() = TestCaseResponse(id!!, name, inputs.toList(), outputs.toList())
}
