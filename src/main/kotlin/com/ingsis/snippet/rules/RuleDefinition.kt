package com.ingsis.snippet.rules

enum class RuleType { FORMATTING, LINTING }

/** Lo que una regla lleva además de estar activa o no. */
enum class ValueKind { NONE, NUMBER, TEXT }

/**
 * Las reglas predeterminadas de PrintScript, las únicas que se pueden habilitar por ahora (US11,
 * US14). [key] es el nombre de la regla en la configuración del lenguaje: así se la muestra al
 * usuario y así viaja a language-service.
 *
 * Las reglas de un mismo [exclusiveGroup] se contradicen, así que a lo sumo una puede estar activa.
 */
enum class RuleDefinition(
    val type: RuleType,
    val key: String,
    val kind: ValueKind = ValueKind.NONE,
    val defaultValue: String? = null,
    val options: Set<String> = emptySet(),
    val exclusiveGroup: String? = null,
) {
    SPACE_BEFORE_COLON(RuleType.FORMATTING, "enforce-spacing-before-colon-in-declaration"),
    SPACE_AFTER_COLON(RuleType.FORMATTING, "enforce-spacing-after-colon-in-declaration"),
    SPACE_AROUND_EQUALS(RuleType.FORMATTING, "enforce-spacing-around-equals", exclusiveGroup = "equals"),
    NO_SPACE_AROUND_EQUALS(RuleType.FORMATTING, "enforce-no-spacing-around-equals", exclusiveGroup = "equals"),
    SINGLE_SPACE_SEPARATION(RuleType.FORMATTING, "mandatory-single-space-separation"),
    SPACE_SURROUNDING_OPERATIONS(RuleType.FORMATTING, "mandatory-space-surrounding-operations"),
    LINE_BREAK_AFTER_STATEMENT(RuleType.FORMATTING, "mandatory-line-break-after-statement"),
    LINE_BREAKS_AFTER_PRINTLN(RuleType.FORMATTING, "line-breaks-after-println", ValueKind.NUMBER, defaultValue = "1"),
    BRACE_SAME_LINE(RuleType.FORMATTING, "if-brace-same-line", exclusiveGroup = "brace"),
    BRACE_BELOW_LINE(RuleType.FORMATTING, "if-brace-below-line", exclusiveGroup = "brace"),
    INDENT_INSIDE_IF(RuleType.FORMATTING, "indent-inside-if", ValueKind.NUMBER, defaultValue = "4"),

    IDENTIFIER_FORMAT(
        RuleType.LINTING,
        "identifier_format",
        ValueKind.TEXT,
        defaultValue = "camel case",
        options = setOf("camel case", "snake case"),
    ),
    PRINTLN_ARGUMENT(RuleType.LINTING, "mandatory-variable-or-literal-in-println"),
    READ_INPUT_ARGUMENT(RuleType.LINTING, "mandatory-variable-or-literal-in-readInput"),
    ;

    /** El valor como lo espera la configuración del lenguaje: `true` para las que no llevan valor. */
    fun configValue(value: String?): Any =
        when (kind) {
            ValueKind.NONE -> true
            ValueKind.NUMBER -> (value ?: defaultValue!!).toInt()
            ValueKind.TEXT -> value ?: defaultValue!!
        }

    /** El valor que manda el usuario, normalizado; `null` si no lleva o no mandó ninguno. */
    fun normalize(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        return when (kind) {
            ValueKind.NONE -> null
            ValueKind.NUMBER -> trimmed?.also { if ((it.toIntOrNull() ?: -1) < 0) throw InvalidRuleValueException(key, it) }
            ValueKind.TEXT -> trimmed?.lowercase()?.also { if (it !in options) throw InvalidRuleValueException(key, it) }
        }
    }

    companion object {
        fun of(type: RuleType): List<RuleDefinition> = entries.filter { it.type == type }

        fun find(
            type: RuleType,
            key: String,
        ): RuleDefinition = entries.find { it.type == type && it.key == key } ?: throw UnknownRuleException(key)
    }
}

class UnknownRuleException(
    key: String,
) : RuntimeException("No existe la regla $key")

class InvalidRuleValueException(
    key: String,
    value: String,
) : RuntimeException("$value no es un valor válido para la regla $key")

class ConflictingRulesException(
    keys: List<String>,
) : RuntimeException("Estas reglas se contradicen y no pueden estar activas a la vez: ${keys.joinToString()}")
