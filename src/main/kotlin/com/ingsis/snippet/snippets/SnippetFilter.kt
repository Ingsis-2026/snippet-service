package com.ingsis.snippet.snippets

import com.ingsis.snippet.clients.Role
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import java.util.UUID

/** La relación del usuario con los snippets que quiere ver (US5): los suyos, los compartidos o ambos. */
enum class Relation(
    val role: Role?,
) {
    ALL(null),
    OWNER(Role.OWNER),
    SHARED(Role.READ),
}

/**
 * Cómo se navega la lista de snippets (US5). El nombre se busca como parte del nombre y el
 * lenguaje se compara entero; ninguno distingue mayúsculas. Lo que es `null` no filtra.
 */
data class SnippetFilter(
    val relation: Relation = Relation.ALL,
    val name: String? = null,
    val language: String? = null,
    val status: ValidityStatus? = null,
) {
    /** Solo entre [ids], los snippets que el usuario puede ver según permission-service. */
    fun among(ids: Collection<UUID>): Specification<Snippet> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>(root.get<UUID>("id").`in`(ids))
            name?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.like(cb.lower(root.get("name")), "%${it.lowercase()}%")
            }
            language?.takeIf { it.isNotBlank() }?.let {
                predicates += cb.equal(cb.lower(root.get("language")), it.lowercase())
            }
            status?.let { predicates += cb.equal(root.get<ValidityStatus>("status"), it) }
            cb.and(*predicates.toTypedArray())
        }

    companion object {
        /** Se ordena por los mismos campos por los que se filtra. */
        val SORTABLE = setOf("name", "language", "status")

        fun requireSortable(sort: Sort) {
            sort.map { it.property }.firstOrNull { it !in SORTABLE }?.let { throw InvalidSortException(it) }
        }
    }
}

class InvalidSortException(
    property: String,
) : RuntimeException("No se puede ordenar por $property; se puede por ${SnippetFilter.SORTABLE.joinToString()}")
