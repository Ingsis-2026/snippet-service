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
import java.time.Instant
import java.util.UUID

/**
 * Si el snippet cumple las reglas de lint de su owner (US5). Arranca `PENDING` hasta que se lintee.
 * `INVALID` es que ya no es válido para su parser: se guardó con una versión anterior y la actual
 * no lo acepta, así que ni siquiera se puede lintear.
 *
 * `ERROR` es que no se lo pudo evaluar: el linteo falló todas las veces que se reintentó y el worker
 * lo salteó (ver [com.ingsis.snippet.streams.RedisSnippetWorker]). No dice nada de si cumple las
 * reglas. Sale de ahí la próxima vez que se lo lintea: cuando se lo actualiza, cuando su owner cambia
 * las reglas o cuando hay un parser nuevo.
 *
 * Se guarda con su nombre, así que en la API se ve tal cual (`"ERROR"`). Agregar un estado acá
 * alcanza: [ValidityStatusConstraint] pone al día el check de la base.
 */
enum class ValidityStatus { PENDING, COMPLIANT, NOT_COMPLIANT, INVALID, ERROR }

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
    /**
     * Desde cuándo está en [status]. Con esto se encuentran los que quedaron `PENDING` porque se perdió
     * el mensaje que los iba a lintear (ver [com.ingsis.snippet.linting.StuckLintSweeper]). Es `null` en
     * los snippets de antes de que existiera la columna.
     */
    var statusChangedAt: Instant? = Instant.now(),
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

/** Cuántos snippets hay en un estado. */
interface StatusCount {
    val status: ValidityStatus
    val total: Long
}

/**
 * Todo lo que cambia el estado de un snippet guarda también cuándo (`statusChangedAt`). Ese momento
 * ([at]) tiene default para que quien llama no tenga que pasarlo; los tests lo pasan para simular un
 * cambio viejo.
 */
interface SnippetRepository :
    JpaRepository<Snippet, UUID>,
    JpaSpecificationExecutor<Snippet> {
    @Query("select s.id from Snippet s where s.ownerId = :ownerId")
    fun findIdsByOwnerId(ownerId: String): List<UUID>

    @Query("select s.id from Snippet s")
    fun findAllIds(): List<UUID>

    fun countByOwnerId(ownerId: String): Long

    @Query("select s.status as status, count(s) as total from Snippet s group by s.status")
    fun countByStatus(): List<StatusCount>

    @Query("select count(distinct s.ownerId) from Snippet s")
    fun countOwners(): Long

    /** Todos los snippets, de todos los usuarios, vuelven a `PENDING` hasta que se los valide con el parser nuevo. */
    @Transactional
    @Modifying
    @Query("update Snippet s set s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING, s.statusChangedAt = :at")
    fun markAllPending(at: Instant = Instant.now()): Int

    /** Todos los snippets del owner vuelven a `PENDING` hasta que se los lintee con sus reglas nuevas (US15). */
    @Transactional
    @Modifying
    @Query(
        "update Snippet s set s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING, s.statusChangedAt = :at " +
            "where s.ownerId = :ownerId",
    )
    fun markPendingByOwner(
        ownerId: String,
        at: Instant = Instant.now(),
    ): Int

    /**
     * Cambia solo el estado. Guardar la entidad entera pisaría con datos viejos un update del
     * snippet que haya entrado mientras se lo linteaba.
     */
    @Transactional
    @Modifying
    @Query("update Snippet s set s.status = :status, s.statusChangedAt = :at where s.id = :id")
    fun updateStatus(
        id: UUID,
        status: ValidityStatus,
        at: Instant = Instant.now(),
    ): Int

    /**
     * `ERROR` solo si sigue `PENDING`: si mientras tanto otro mensaje del mismo snippet lo linteó, se
     * queda con ese resultado.
     */
    @Transactional
    @Modifying
    @Query(
        "update Snippet s set s.status = com.ingsis.snippet.snippets.ValidityStatus.ERROR, s.statusChangedAt = :at " +
            "where s.id = :id and s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING",
    )
    fun markError(
        id: UUID,
        at: Instant = Instant.now(),
    ): Int

    /** Los que están `PENDING` desde antes de [before], o desde antes de que se guardara cuándo. */
    @Query(
        "select s.id from Snippet s where s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING " +
            "and (s.statusChangedAt is null or s.statusChangedAt < :before)",
    )
    fun findIdsPendingSince(before: Instant): List<UUID>

    /**
     * Vuelve a contar desde [at] el tiempo que llevan `PENDING`, así la próxima vuelta del
     * [com.ingsis.snippet.linting.StuckLintSweeper] no los vuelve a encolar. Los que ya se lintearon
     * no se tocan.
     */
    @Transactional
    @Modifying
    @Query(
        "update Snippet s set s.statusChangedAt = :at " +
            "where s.id in :ids and s.status = com.ingsis.snippet.snippets.ValidityStatus.PENDING",
    )
    fun restartPending(
        ids: Collection<UUID>,
        at: Instant = Instant.now(),
    ): Int
}
