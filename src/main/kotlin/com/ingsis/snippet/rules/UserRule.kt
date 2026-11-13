package com.ingsis.snippet.rules

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Cómo dejó un usuario una regla. Solo se guardan las que tocó: las demás están apagadas y con su
 * valor por defecto.
 */
@Entity
@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "rule_key"])])
class UserRule(
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "rule_key", nullable = false)
    val key: String,
    @Column(nullable = false)
    var active: Boolean,
    @Column(name = "rule_value")
    var value: String?,
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
)

interface UserRuleRepository : JpaRepository<UserRule, UUID> {
    fun findAllByUserId(userId: String): List<UserRule>
}
