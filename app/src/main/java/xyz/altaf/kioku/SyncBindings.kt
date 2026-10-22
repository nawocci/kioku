package xyz.altaf.kioku

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConcurrencyModel
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import xyz.altaf.kioku.sync.db.SyncDatabase

/**
 * The sync bookkeeping database: server identity, device id, clock, and the
 * per-record positions. It lives beside the main database and is provided by
 * the fork only; upstream code never sees it.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object SyncBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesSyncDatabase(context: Context): SyncDatabase {
        val driver: SqlDriver = AndroidxSqliteDriver(
            connectionFactory = object : AndroidxSqliteConnectionFactory {
                override val driver: SQLiteDriver = BundledSQLiteDriver()
                override fun createConnection(name: String): SQLiteConnection = driver.open(name)
            },
            databaseType = AndroidxSqliteDatabaseType.FileProvider {
                context.getDatabasePath("kioku-sync.db").path
            },
            schema = SyncDatabase.Schema,
            configuration = AndroidxSqliteConfiguration(
                concurrencyModel = AndroidxSqliteConcurrencyModel.MultipleReadersSingleWriter(isWal = true),
            ),
        )
        return SyncDatabase(driver)
    }
}
