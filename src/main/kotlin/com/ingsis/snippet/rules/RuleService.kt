package com.ingsis.snippet.rules

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Cómo está una regla para un usuario. [value] es `null` en las que no llevan valor. */
data class RuleState(
    val definition: RuleDefinition,
    val active: Boolean,
    val value: String?,
)

/** Un cambio a una regla. Sin [value], una regla que lleva valor conserva el que tenía. */
data class RuleChange(
    val key: String,
    val active: Boolean,
    val value: String?,
)

/**
 * Las reglas que elige cada usuario (US11, US14). Valen para todos los snippets de los que es
 * owner, y se le pasan a language-service en cada llamada.
 */
@Service
class RuleService(
    private val repository: UserRuleRepository,
) {
    /** Todas las reglas del tipo, con el estado que eligió el usuario o apagadas si no tocó la regla. */
    @Transactional(readOnly = true)
    fun rulesOf(
        userId: String,
        type: RuleType,
    ): List<RuleState> {
        val saved = repository.findAllByUserId(userId).associateBy { it.key }
        return RuleDefinition.of(type).map { definition ->
            val rule = saved[definition.key]
            RuleState(definition, rule?.active ?: false, rule?.value ?: definition.defaultValue)
        }
    }

    /**
     * Habilita, deshabilita o cambia el valor de las reglas de [changes]; las demás quedan como
     * estaban. Si un cambio no es válido no se guarda ninguno.
     */
    @Transactional
    fun update(
        userId: String,
        type: RuleType,
        changes: List<RuleChange>,
    ): List<RuleState> {
        val saved = repository.findAllByUserId(userId).associateBy { it.key }.toMutableMap()
        for (change in changes) {
            val definition = RuleDefinition.find(type, change.key)
            val value = definition.normalize(change.value)
            val rule = saved.getOrPut(definition.key) { UserRule(userId, definition.key, change.active, value) }
            rule.active = change.active
            if (value != null) rule.value = value
        }
        requireCompatible(type, saved.values)
        repository.saveAll(saved.values)
        return rulesOf(userId, type)
    }

    /**
     * Las reglas activas del usuario como las espera language-service: el nombre de cada una con
     * su valor, o `true` si no lleva.
     */
    @Transactional(readOnly = true)
    fun configOf(
        userId: String,
        type: RuleType,
    ): Map<String, Any> =
        rulesOf(userId, type)
            .filter { it.active }
            .associate { it.definition.key to it.definition.configValue(it.value) }

    private fun requireCompatible(
        type: RuleType,
        rules: Collection<UserRule>,
    ) {
        val active = rules.filter { it.active }.map { it.key }.toSet()
        RuleDefinition
            .of(type)
            .filter { it.exclusiveGroup != null && it.key in active }
            .groupBy { it.exclusiveGroup }
            .values
            .firstOrNull { it.size > 1 }
            ?.let { conflicting -> throw ConflictingRulesException(conflicting.map { it.key }) }
    }
}
