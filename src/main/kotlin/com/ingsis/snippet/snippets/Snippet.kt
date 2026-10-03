package com.ingsis.snippet.snippets

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Si el snippet cumple las reglas de lint de su owner (US5). Arranca `PENDING` hasta que se lintee. */
enum class ValidityStatus { PENDING, COMPLIANT, NOT_COMPLIANT }

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
    @Column(nullable = false, columnDefinition = "text")
    var content: String,
    @Column(nullable = false)
    val ownerId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ValidityStatus = ValidityStatus.PENDING,
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

interface SnippetRepository : JpaRepository<Snippet, UUID>
