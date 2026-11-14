package com.ingsis.snippet

import com.ingsis.snippet.clients.LanguageClient
import com.ingsis.snippet.clients.PermissionClient
import com.ingsis.snippet.snippets.Snippet
import com.ingsis.snippet.snippets.SnippetRepository
import com.ingsis.snippet.snippets.ValidityStatus
import com.ingsis.snippet.snippets.ValidityStatusConstraint
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import kotlin.test.assertEquals

/** El check de la columna `status` acepta todos los estados, también los que se agregaron después de crear la tabla. */
@SpringBootTest
@ActiveProfiles("test")
class ValidityStatusConstraintTest(
    @Autowired private val constraint: ValidityStatusConstraint,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val snippets: SnippetRepository,
) {
    @MockitoBean
    private lateinit var languageClient: LanguageClient

    @MockitoBean
    private lateinit var permissionClient: PermissionClient

    @AfterEach
    fun cleanUp() {
        snippets.deleteAll()
        constraint.update()
    }

    private fun checks() =
        jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints where lower(constraint_name) = 'snippet_status_check'",
            Long::class.java,
        )

    @Test
    fun `al arrancar queda un check con todos los estados`() {
        assertEquals(1L, checks())
        ValidityStatus.entries.forEach { snippets.save(Snippet("Con $it", "d", "printscript", "1.1", "auth0|ana", it)) }
    }

    @Test
    fun `reemplaza el check que habia, aunque le falten estados`() {
        jdbc.execute("alter table snippet drop constraint snippet_status_check")
        jdbc.execute("alter table snippet add constraint snippet_status_check check (status in ('PENDING'))")

        constraint.update()

        assertEquals(1L, checks())
        snippets.save(Snippet("Con error", "d", "printscript", "1.1", "auth0|ana", ValidityStatus.ERROR))
    }
}
