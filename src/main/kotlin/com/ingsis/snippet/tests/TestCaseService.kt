package com.ingsis.snippet.tests

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
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
 * El resultado de correr un test: pasa si el snippet termina sin error e imprime exactamente los
 * outputs esperados, en el mismo orden.
 */
data class TestRunResponse(
    val passed: Boolean,
    val expected: List<String>,
    val actual: List<String>,
    val error: CodeError?,
)

/**
 * Devuelve [TestCaseResponse] y no la entidad: las listas se cargan de forma perezosa y tienen
 * que leerse dentro de la transacción.
 */
@Service
class TestCaseService(
    private val repository: TestCaseRepository,
    private val access: SnippetAccess,
    private val languageClient: LanguageClient,
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

    /**
     * US6: correr un test desde la vista del snippet. Lo puede correr cualquiera que vea el
     * snippet (US9 habla de "un snippet al que tengo acceso"). Se espera a que termine: ver el
     * output a medida que se evalúa es US9, que se ve más adelante.
     */
    @Transactional(readOnly = true)
    fun run(
        snippetId: UUID,
        testId: UUID,
        userId: String,
    ): TestRunResponse {
        val snippet = access.requireReader(snippetId, userId)
        val test = repository.findByIdAndSnippetId(testId, snippetId) ?: throw TestCaseNotFoundException(testId)
        val outcome = languageClient.run(snippet.content, snippet.language, snippet.version, test.inputs.toList())
        return TestRunResponse(
            passed = outcome.error == null && outcome.outputs == test.outputs,
            expected = test.outputs.toList(),
            actual = outcome.outputs,
            error = outcome.error,
        )
    }

    private fun TestCase.toResponse() = TestCaseResponse(id!!, name, inputs.toList(), outputs.toList())
}

class TestCaseNotFoundException(
    id: UUID,
) : RuntimeException("No existe el test $id en este snippet")
