package com.ingsis.snippet.rules

import com.fasterxml.jackson.annotation.JsonProperty
import com.ingsis.snippet.snippets.USER_HEADER
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Una regla como la ve el usuario. [value] es `null` en las que no llevan valor. */
data class RuleResponse(
    val name: String,
    @get:JsonProperty("isActive") val isActive: Boolean,
    val value: String?,
)

/** El valor llega como texto aunque sea un número, igual que en la configuración del lenguaje. */
data class RuleRequest(
    @field:NotBlank val name: String,
    @param:JsonProperty("isActive") val isActive: Boolean,
    val value: String? = null,
) {
    fun toChange() = RuleChange(name, isActive, value)
}

/**
 * US11 y US14: ver y cambiar las reglas de formato y de lint del usuario. El `PUT` recibe solo
 * las reglas que cambian y devuelve todas.
 */
@RestController
class RuleController(
    private val service: RuleService,
) {
    @GetMapping("/rules/formatting")
    fun formatting(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
    ): List<RuleResponse> = service.rulesOf(userId, RuleType.FORMATTING).map { it.toResponse() }

    @PutMapping("/rules/formatting")
    fun updateFormatting(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @Valid @RequestBody rules: List<RuleRequest>,
    ): List<RuleResponse> = service.update(userId, RuleType.FORMATTING, rules.map { it.toChange() }).map { it.toResponse() }

    @GetMapping("/rules/linting")
    fun linting(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
    ): List<RuleResponse> = service.rulesOf(userId, RuleType.LINTING).map { it.toResponse() }

    @PutMapping("/rules/linting")
    fun updateLinting(
        @RequestHeader(USER_HEADER) @NotBlank userId: String,
        @Valid @RequestBody rules: List<RuleRequest>,
    ): List<RuleResponse> = service.update(userId, RuleType.LINTING, rules.map { it.toChange() }).map { it.toResponse() }

    private fun RuleState.toResponse() = RuleResponse(definition.key, active, value)
}
