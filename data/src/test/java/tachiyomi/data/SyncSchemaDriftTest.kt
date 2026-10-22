package tachiyomi.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The capture triggers exist twice: once in the fresh-install schema
 * (`sync.sq`) and once in migration 100 (`100.sqm`) for upgrades. The two
 * copies drifted once already — the capture flag was seeded in one and not
 * the other, silently disabling capture on fresh installs. This test keeps
 * the duplication honest by comparing every CREATE statement from both
 * files after normalizing whitespace.
 */
class SyncSchemaDriftTest {

    private fun createStatements(path: String): Map<String, String> {
        val text = File(path).readText()
        val statements = mutableMapOf<String, String>()
        val regex =
            Regex("CREATE (TRIGGER|TABLE|UNIQUE INDEX|INDEX)\\s+(?:IF NOT EXISTS\\s+)?[\\w`]+", RegexOption.IGNORE_CASE)
        for (match in regex.findAll(text)) {
            val name = match.value.substringAfterLast(' ').trim('`')
            // A trigger body ends at its END; table and index statements end
            // at the next semicolon outside of a BEGIN block.
            var end = match.range.last + 1
            if (match.value.startsWith("CREATE TRIGGER", ignoreCase = true)) {
                end = text.indexOf("END;", match.range.last)
            } else {
                while (true) {
                    end = text.indexOf(';', end)
                    if (end == -1) break
                    val body = text.substring(match.range.first, end)
                    if (body.count { it == '(' } <= body.count { it == ')' }) break
                    end++
                }
            }
            if (end == -1) continue
            val body = text.substring(match.range.first, end + 1)
            statements[name] = body.replace(Regex("\\s+"), " ").trim()
        }
        return statements
    }

    @Test
    fun `the migration creates exactly what the fresh schema creates`() {
        val fresh = createStatements("src/main/sqldelight/tachiyomi/data/sync.sq")
        val migrated = createStatements("src/main/sqldelight/tachiyomi/migrations/100.sqm")

        // The fresh schema also carries the query catalog; only the DDL is
        // duplicated, and only that must agree.
        assertEquals(fresh.keys, migrated.keys, "the migration and the schema define different sync objects")

        for ((name, body) in migrated) {
            assertEquals(body, fresh.getValue(name), "CREATE $name drifted between the migration and the fresh schema")
        }
    }
}
