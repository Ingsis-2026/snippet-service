package com.ingsis.snippet.snippets

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

/**
 * Hasta que se vea Auth0 en clase, el usuario llega en este header. Después va a salir del
 * `sub` del JWT, y este header desaparece.
 */
const val USER_HEADER = "X-User-Id"

/** Un snippet escrito en el editor (US3, US4): el código llega como texto en vez de como archivo. */
data class SnippetRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val description: String,
    @field:NotBlank val language: String,
    @field:NotBlank val version: String,
    @field:NotBlank val content: String,
) {
    fun toData() = SnippetData(name, description, language, version, content)
}

data class SnippetResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
    val ownerId: String,
    val status: ValidityStatus,
)

fun Snippet.toResponse() = SnippetResponse(id!!, name, description, language, version, content, ownerId, status)

/**
 * Cada operación existe en dos formas que solo difieren en cómo llega el código: como archivo
 * (multipart) o desde el editor (JSON). Las dos terminan en el mismo [SnippetService].
 */
@RestController
class SnippetController(
    private val service: SnippetService,
) {
    /** US1: crear un snippet subiendo un archivo, junto con sus datos. */
    @PostMapping("/snippets", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createFromFile(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @RequestParam @NotBlank name: String,
        @RequestParam @NotBlank description: String,
        @RequestParam @NotBlank language: String,
        @RequestParam @NotBlank version: String,
        @RequestParam file: MultipartFile,
    ): SnippetResponse = service.create(userId, SnippetData(name, description, language, version, file.text())).toResponse()

    /** US3: crear un snippet escrito en el editor. */
    @PostMapping("/snippets", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun createFromEditor(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @Valid @RequestBody request: SnippetRequest,
    ): SnippetResponse = service.create(userId, request.toData()).toResponse()

    /** US6: ver un snippet. Sus tests se piden aparte, en `GET /snippets/{id}/tests`. */
    @GetMapping("/snippets/{id}")
    fun get(
        @PathVariable id: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
    ): SnippetResponse = service.get(id, userId).toResponse()

    /** US2: actualizar un snippet subiendo un archivo. Se mandan todos los datos, cambien o no. */
    @PutMapping("/snippets/{id}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateFromFile(
        @PathVariable id: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @RequestParam @NotBlank name: String,
        @RequestParam @NotBlank description: String,
        @RequestParam @NotBlank language: String,
        @RequestParam @NotBlank version: String,
        @RequestParam file: MultipartFile,
    ): SnippetResponse = service.update(id, userId, SnippetData(name, description, language, version, file.text())).toResponse()

    /** US4: actualizar un snippet desde el editor. */
    @PutMapping("/snippets/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun updateFromEditor(
        @PathVariable id: UUID,
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @Valid @RequestBody request: SnippetRequest,
    ): SnippetResponse = service.update(id, userId, request.toData()).toResponse()

    private fun MultipartFile.text(): String {
        if (isEmpty) throw EmptyFileException()
        return bytes.toString(Charsets.UTF_8)
    }
}

class EmptyFileException : RuntimeException("El archivo está vacío")
