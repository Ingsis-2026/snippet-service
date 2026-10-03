package com.ingsis.snippet.tests

import com.ingsis.snippet.snippets.USER_HEADER
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Un snippet sin inputs, o que no imprime nada, tiene listas vacías: por eso tienen default. */
data class TestCaseRequest(
    @field:NotBlank val name: String,
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
) {
    fun toData() = TestCaseData(name, inputs, outputs)
}

@RestController
class TestCaseController(
    private val service: TestCaseService,
) {
    /** US8: crear un test para un snippet propio. */
    @PostMapping("/snippets/{snippetId}/tests")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable snippetId: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @Valid @RequestBody request: TestCaseRequest,
    ): TestCaseResponse = service.create(snippetId, userId, request.toData())

    /** US6: correr un test y ver si pasa. */
    @PostMapping("/snippets/{snippetId}/tests/{testId}/run")
    fun run(
        @PathVariable snippetId: UUID,
        @PathVariable testId: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
    ): TestRunResponse = service.run(snippetId, testId, userId)

    @GetMapping("/snippets/{snippetId}/tests")
    fun list(
        @PathVariable snippetId: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
    ): List<TestCaseResponse> = service.list(snippetId, userId)
}
