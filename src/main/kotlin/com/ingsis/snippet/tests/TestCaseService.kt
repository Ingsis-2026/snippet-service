package com.ingsis.snippet.tests

import com.ingsis.snippet.clients.CodeError
import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.RunOutcome
import com.ingsis.snippet.snippets.SnippetAccess
import com.ingsis.snippet.snippets.SnippetUpdatedEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
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
    val status: TestStatus,
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
     *
     * El resultado queda guardado en el test, igual que cuando se corre solo (US16).
     */
    @Transactional
    fun run(
        snippetId: UUID,
        testId: UUID,
        userId: String,
    ): TestRunResponse {
        val snippet = access.requireReader(snippetId, userId)
        val test = repository.findByIdAndSnippetId(testId, snippetId) ?: throw TestCaseNotFoundException(testId)
        val outcome = languageClient.run(snippet.content, snippet.language, snippet.version, test.inputs.toList())
        val passed = test.record(outcome)
        return TestRunResponse(passed, test.outputs.toList(), outcome.outputs, outcome.error)
    }

    /**
     * US16: cada vez que se actualiza un snippet se corren todos sus tests. Corre después de que
     * el update se guardó, en su propia transacción, así que ningún resultado lo modifica ni lo
     * deshace. Un test que no se pudo correr queda `PENDING` y no frena a los demás.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun runAll(event: SnippetUpdatedEvent) {
        val snippet = event.snippet
        for (test in repository.findAllBySnippetId(snippet.id!!)) {
            try {
                test.record(languageClient.run(snippet.content, snippet.language, snippet.version, test.inputs.toList()))
            } catch (e: RuntimeException) {
                log.warn("No se pudo correr el test ${test.id} del snippet ${snippet.id}", e)
                test.status = TestStatus.PENDING
            }
        }
    }

    /**
     * Un test pasa si el snippet termina sin error e imprime exactamente los outputs esperados,
     * en el mismo orden. Devuelve si pasó y lo deja guardado en el test.
     */
    private fun TestCase.record(outcome: RunOutcome): Boolean {
        val passed = outcome.error == null && outcome.outputs == outputs
        status = if (passed) TestStatus.PASSED else TestStatus.FAILED
        return passed
    }

    private fun TestCase.toResponse() = TestCaseResponse(id!!, name, inputs.toList(), outputs.toList(), status)

    private companion object {
        val log: Logger = LoggerFactory.getLogger(TestCaseService::class.java)
    }
}

class TestCaseNotFoundException(
    id: UUID,
) : RuntimeException("No existe el test $id en este snippet")
