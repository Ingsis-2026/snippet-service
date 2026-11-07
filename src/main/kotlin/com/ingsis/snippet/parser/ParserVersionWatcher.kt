package com.ingsis.snippet.parser

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.linting.LintQueue
import com.ingsis.snippet.snippets.SnippetRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClientException
import kotlin.jvm.optionals.getOrNull

/** La versión del parser con la que se validaron todos los snippets por última vez. Hay una sola fila. */
@Entity
class ParserVersion(
    @Column(nullable = false)
    var version: String,
    @Id
    val id: Int = ID,
) {
    companion object {
        const val ID = 1
    }
}

interface ParserVersionRepository : JpaRepository<ParserVersion, Int> {
    /** Solo si sigue siendo [old]: si otra réplica ya la cambió, no se pisa. */
    @Transactional
    @Modifying
    @Query("update ParserVersion p set p.version = :new where p.id = 1 and p.version = :old")
    fun replace(
        old: String,
        new: String,
    ): Int
}

/**
 * "Con cada versión nueva del parser del lenguaje, se deberán re-evaluar y validar todos los
 * snippets." language-service dice con qué versión de la librería de PrintScript está corriendo;
 * cuando no es la última con la que se validó, todos los snippets vuelven a `PENDING` y se encolan
 * para lintear. El worker de US15 valida cada uno antes de lintearlo, así que los que la versión
 * nueva no acepta quedan `INVALID`.
 *
 * Es el mismo mecanismo que cuando un usuario cambia sus reglas de lint, con la misma tolerancia
 * a fallos: cada snippet es un mensaje que se reintenta, y se saltea si sigue fallando.
 */
@Component
class ParserVersionWatcher(
    private val languageClient: LanguageClient,
    private val versions: ParserVersionRepository,
    private val snippets: SnippetRepository,
    private val lintQueue: LintQueue,
) {
    /**
     * La versión nueva se guarda recién después de encolar todos los snippets: si algo falla antes
     * (language-service o Redis caídos, o el servicio que se cae a mitad de camino), la próxima
     * vuelta ve la versión vieja y lo hace de nuevo. Encolar dos veces no cambia el resultado,
     * porque lintear es idempotente, y lo mismo vale si dos réplicas se dan cuenta a la vez.
     *
     * La primera vez no hay versión guardada: se validan todos, porque no se sabe con qué parser
     * se validaron los que ya estaban.
     */
    fun check() {
        val current =
            try {
                languageClient.parserVersion()
            } catch (e: RestClientException) {
                // Sin el stack trace: con language-service caído esto se repite en cada vuelta.
                log.warn("No se pudo consultar la versión del parser a language-service: ${e.message}")
                return
            }
        val previous = versions.findById(ParserVersion.ID).getOrNull()?.version
        if (previous == current) return

        log.info("Versión nueva del parser: ${previous ?: "ninguna"} -> $current. Se vuelven a validar todos los snippets")
        try {
            snippets.markAllPending()
            lintQueue.enqueue(snippets.findAllIds())
            record(previous, current)
        } catch (e: DataAccessException) {
            log.warn("No se pudieron encolar los snippets para validarlos con el parser $current; se reintenta en la próxima vuelta", e)
        }
    }

    private fun record(
        previous: String?,
        current: String,
    ) {
        if (previous != null) {
            versions.replace(previous, current)
            return
        }
        try {
            versions.saveAndFlush(ParserVersion(current))
        } catch (e: DataIntegrityViolationException) {
            // Otra réplica guardó la primera versión a la vez: los snippets ya quedaron encolados igual.
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(ParserVersionWatcher::class.java)
    }
}

/**
 * Consulta la versión al arrancar y cada `parser.check-interval`. Con `parser.watch.enabled=false`
 * no se consulta sola: los tests llaman a [ParserVersionWatcher.check] cuando quieren.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("parser.watch.enabled", havingValue = "true", matchIfMissing = true)
class ParserVersionSchedule(
    private val watcher: ParserVersionWatcher,
) {
    @Scheduled(initialDelayString = "\${parser.initial-delay:30s}", fixedDelayString = "\${parser.check-interval:5m}")
    fun check() = watcher.check()
}
