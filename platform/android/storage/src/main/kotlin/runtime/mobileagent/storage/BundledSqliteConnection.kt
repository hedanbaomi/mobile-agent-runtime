// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow

class AndroidContextSqlite(
    context: Context,
    name: String = "mobile-agent.db",
) : SqlConnection {
    private val helper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build(),
    )
    private val db: SupportSQLiteDatabase = helper.writableDatabase

    override fun execute(sql: String, args: List<Any?>) {
        db.execSQL(sql, args.toTypedArray())
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        val cursor = db.query(sql, args.toTypedArray())
        cursor.use {
            val rows = mutableListOf<SqlRow>()
            val names = Array(it.columnCount) { idx -> it.getColumnName(idx) }
            while (it.moveToNext()) {
                val map = linkedMapOf<String, Any?>()
                names.forEachIndexed { idx, column ->
                    map[column] = when (it.getType(idx)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> it.getLong(idx)
                        Cursor.FIELD_TYPE_FLOAT -> it.getDouble(idx)
                        Cursor.FIELD_TYPE_BLOB -> it.getBlob(idx)
                        else -> it.getString(idx)
                    }
                }
                rows += SqlRow(map)
            }
            return rows
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }
}
