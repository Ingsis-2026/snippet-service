package com.ingsis.snippet.snippets

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * En Postgres, Hibernate crea la columna `status` con un check con los estados que había al crear la
 * tabla (`check (status in ('PENDING', ...))`), y `ddl-auto: update` no lo vuelve a tocar. En una base
 * de antes de `ERROR`, guardar un snippet con ese estado fallaría. Por eso, al arrancar y antes de que
 * corra cualquier otra cosa, se reemplaza el check por uno con todos los estados de [ValidityStatus].
 *
 * Postgres le pone `snippet_status_check` al check que crea Hibernate. Si no existe (en H2, donde la
 * columna es de un tipo enum propio y no tiene check), solo se agrega el nuevo. Las dos sentencias van
 * en una transacción, y en Postgres el DDL es transaccional: dos réplicas que arrancan a la vez se
 * esperan una a la otra en vez de pisarse.
 */
@Component
@DependsOn("entityManagerFactory")
class ValidityStatusConstraint(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @PostConstruct
    fun update() {
        val statuses = ValidityStatus.entries.joinToString { "'${it.name}'" }
        transaction.executeWithoutResult {
            jdbc.execute("alter table snippet drop constraint if exists $NAME")
            jdbc.execute("alter table snippet add constraint $NAME check (status in ($statuses))")
        }
    }

    private companion object {
        const val NAME = "snippet_status_check"
    }
}
