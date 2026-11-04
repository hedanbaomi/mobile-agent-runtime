// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.Locale
import runtime.mobileagent.domain.AppError
import runtime.mobileagent.domain.ErrorCode
import runtime.mobileagent.domain.RetryClass

/** Exact known SQL grammar; formatting may vary, quoted values must remain byte-exact. */
internal object RootReceiptSchemaVerifier {
    fun verifyExisting(db: SqlConnection, tableSql: String, adjuncts: Map<String,String>) {
        val stored = db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='root_dispatch_receipts'")
            .singleOrNull()?.string("sql") ?: return
        requireSupported(tokens(stored) == tokens(tableSql))
        val existing = db.query("SELECT name,sql FROM sqlite_master WHERE tbl_name='root_dispatch_receipts' AND type IN('index','trigger') AND sql IS NOT NULL")
        existing.forEach { row ->
            val expected = adjuncts[row.string("name")]
            requireSupported(expected != null && tokens(row.string("sql")) == tokens(expected))
        }
    }

    fun install(db: SqlConnection, tableSql: String, adjuncts: Map<String,String>) = db.transaction {
        verifyExisting(db, tableSql, adjuncts)
        db.execute(tableSql)
        adjuncts.values.forEach { db.execute(it) }
        val persisted = db.query("SELECT name,sql FROM sqlite_master WHERE tbl_name='root_dispatch_receipts' AND type IN('index','trigger') AND sql IS NOT NULL")
        check(persisted.associate { it.string("name") to tokens(it.string("sql")) } ==
            adjuncts.mapValues { tokens(it.value) }) { "Root receipt protection was not persisted" }
        check(tokens(db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='root_dispatch_receipts'")
            .single().string("sql")) == tokens(tableSql))
    }

    private fun requireSupported(supported: Boolean) {
        if (!supported) throw AppError(
            ErrorCode.SCHEMA_UNSUPPORTED,
            "Root receipt storage requires an explicit migration",
            RetryClass.USER_ACTION, "migration", "root-receipt-schema",
        ).asException()
    }

    private fun tokens(sql: String): List<String> {
        val result = SQL_TOKEN.findAll(sql).map { match ->
            val token = match.value
            if (token.startsWith("'") || token.startsWith("\"")) token else token.lowercase(Locale.ROOT)
        }.toMutableList()
        if (result.lastOrNull() == ";") result.removeAt(result.lastIndex)
        // SQLite removes IF NOT EXISTS from sqlite_master. Remove only this DDL prefix.
        val kind = if (result.getOrNull(1) == "unique") 2 else 1
        val start = kind+1
        if (result.firstOrNull() == "create" && result.getOrNull(kind) in setOf("table","index","trigger") &&
            result.drop(start).take(3) == listOf("if","not","exists")) {
            repeat(3) { result.removeAt(start) }
        }
        return result
    }

    private val SQL_TOKEN = Regex("""'(?:''|[^'])*'|"(?:""|[^"])*"|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|<>|!=|<=|>=|\S""")
}
