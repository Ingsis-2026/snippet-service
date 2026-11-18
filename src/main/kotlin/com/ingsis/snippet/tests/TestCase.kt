package com.ingsis.snippet.tests

import com.ingsis.snippet.snippets.Snippet
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OrderColumn
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.DynamicUpdate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Cómo le fue a un test la última vez que se corrió (US16). `PENDING` si todavía no se corrió. */
enum class TestStatus { PENDING, PASSED, FAILED }

/**
 * Un test de un snippet (US8): los inputs que se le dan y los outputs que se esperan.
 *
 * El orden importa en las dos listas: los inputs se consumen en orden y los outputs se comparan
 * con cada `println` en el orden en que se llama. Por eso cada lista guarda la posición de cada
 * elemento ([OrderColumn]) en vez de confiar en el orden en que la base devuelve las filas.
 *
 * Al guardarlo se escriben solo las columnas que cambiaron ([DynamicUpdate]): guardar el resultado de
 * correrlo a mano no pisa [pendingRun], que pudo haber cambiado mientras corría (ver [TestCaseRepository.recordRun]).
 */
@Entity
@DynamicUpdate
class TestCase(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snippet_id")
    val snippet: Snippet,
    @Column(nullable = false)
    var name: String,
    @ElementCollection
    @CollectionTable(name = "test_case_input", joinColumns = [JoinColumn(name = "test_case_id")])
    @OrderColumn(name = "position")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    var inputs: MutableList<String>,
    @ElementCollection
    @CollectionTable(name = "test_case_output", joinColumns = [JoinColumn(name = "test_case_id")])
    @OrderColumn(name = "position")
    @Column(name = "content", nullable = false, columnDefinition = "text")
    var outputs: MutableList<String>,
    // El default en la base es para que agregar la columna no falle sobre tests que ya existían.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'PENDING'")
    var status: TestStatus = TestStatus.PENDING,
    /**
     * La corrida automática (US16) que tiene que dejar el resultado: la del último update del snippet
     * ([com.ingsis.snippet.snippets.SnippetUpdatedEvent.revision]). `null` si no hay ninguna en curso, o si se
     * editó el test después, que deja sin valor lo que estuviera corriendo con sus inputs anteriores.
     */
    var pendingRun: UUID? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

interface TestCaseRepository : JpaRepository<TestCase, UUID> {
    fun findAllBySnippetIdOrderByName(snippetId: UUID): List<TestCase>

    fun findAllBySnippetId(snippetId: UUID): List<TestCase>

    fun findAllBySnippetIdAndPendingRun(
        snippetId: UUID,
        pendingRun: UUID,
    ): List<TestCase>

    /**
     * Guarda el resultado de la corrida automática [run], solo si sigue siendo la que tiene que dejarlo: si
     * mientras corría se volvió a actualizar el snippet (o se editó el test), es un resultado viejo y no se
     * guarda. Devuelve cuántos tests cambió: 0 si se descartó.
     *
     * Es un solo `UPDATE` condicional y no leer, comparar y guardar: así no hay un momento entre que se
     * compara y que se guarda en el que pueda entrar el update del snippet.
     */
    @Transactional
    @Modifying
    @Query("update TestCase t set t.status = :status, t.pendingRun = null where t.id = :id and t.pendingRun = :run")
    fun recordRun(
        id: UUID,
        run: UUID,
        status: TestStatus,
    ): Int

    fun findByIdAndSnippetId(
        id: UUID,
        snippetId: UUID,
    ): TestCase?
}
