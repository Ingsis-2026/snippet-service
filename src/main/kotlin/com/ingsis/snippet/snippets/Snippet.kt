package com.ingsis.snippet.snippets

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Si el snippet cumple las reglas de lint de su owner (US5). Arranca `PENDING` hasta que se lintee. */
enum class ValidityStatus { PENDING, COMPLIANT, NOT_COMPLIANT }

/** La metadata de un snippet. Su código se guarda aparte, en el [com.ingsis.snippet.storage.ContentStore]. */
@Entity
class Snippet(
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var description: String,
    @Column(nullable = false)
    var language: String,
    @Column(nullable = false)
    var version: String,
    @Column(nullable = false)
    val ownerId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ValidityStatus = ValidityStatus.PENDING,
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

interface SnippetRepository :
    JpaRepository<Snippet, UUID>,
    JpaSpecificationExecutor<Snippet> {
    @Query("select s.id from Snippet s where s.ownerId = :ownerId")
    fun findIdsByOwnerId(ownerId: String): List<UUID>

    /** Todos los snippets del owner vuelven a `PENDING` hasta que se los lintee con sus reglas nuevas (US15). */
    @Transactional
    @Modifying
    @Query("update Snippet s set s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING where s.ownerId = :ownerId")
    fun markPendingByOwner(ownerId: String): Int

    /**
     * Cambia solo el estado. Guardar la entidad entera pisaría con datos viejos un update del
     * snippet que haya entrado mientras se lo linteaba.
     */
    @Transactional
    @Modifying
    @Query("update Snippet s set s.status = :status where s.id = :id")
    fun updateStatus(
        id: UUID,
        status: ValidityStatus,
    ): Int
}
