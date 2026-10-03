package com.ingsis.snippet.tests

import com.ingsis.snippet.snippets.Snippet
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OrderColumn
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Un test de un snippet (US8): los inputs que se le dan y los outputs que se esperan.
 *
 * El orden importa en las dos listas: los inputs se consumen en orden y los outputs se comparan
 * con cada `println` en el orden en que se llama. Por eso cada lista guarda la posición de cada
 * elemento ([OrderColumn]) en vez de confiar en el orden en que la base devuelve las filas.
 */
@Entity
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
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

interface TestCaseRepository : JpaRepository<TestCase, UUID> {
    fun findAllBySnippetIdOrderByName(snippetId: UUID): List<TestCase>

    fun findByIdAndSnippetId(
        id: UUID,
        snippetId: UUID,
    ): TestCase?
}
