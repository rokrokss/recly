package recly.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import recly.core.platform.JvmRuntime
import recly.core.sync.WorkflowStore

/**
 * docs/10 "스키마 마이그레이션". The emulator found this the hard way: a database created before
 * `secret_sync` existed crashed at launch on `no such table`, because the schema had grown without
 * a version to hang a migration off.
 *
 * These tests build an old database the way the shipped code would have left one — by hand, from
 * the DDL as it stood — and upgrade it. A migration that has drifted from `Rec.sq` fails here
 * rather than on a device.
 */
class MigrationTest {
    /** The number the migrations add up to. `4.sqm` upgrades a version-4 database to this. */
    @Test
    fun `the schema is at version 5`() {
        assertEquals(5L, RecDatabase.Schema.version)
    }

    /**
     * The upgrade this release is: `recording.remote_pending`, what another device says it still
     * has to do (docs/03 "다른 기기의 녹음"). Every row already here reads back with no marker, which
     * is what "nothing is pending" is — including the adopted one, whose column the next pull fills.
     */
    @Test
    fun `a version-4 database gains the pending column and reads its rows as pending nothing`() {
        val path = tempDatabase()
        version1(path, stamped = false)
        JdbcSqliteDriver("jdbc:sqlite:$path").also { seed ->
            seed.execute(null, "DROP TABLE IF EXISTS secret_sync", 0)
            VERSION_4.forEach { seed.execute(null, it, 0) }
            seed.execute(
                null,
                "INSERT INTO recording(id, source, platform, workflow_id, title, started_at, ended_at, duration_sec, " +
                    "timezone, dir, meta_json, status, drive_folder_id, remote) VALUES ('01J9REC', 'phone', 'android', " +
                    "NULL, NULL, '2026-08-26T01:00:00.000Z', NULL, NULL, 'Asia/Seoul', '/data/recordings/x', '{}', " +
                    "'finalized', 'folder-1', 1)",
                0,
            )
            seed.execute(null, "PRAGMA user_version = 4", 0)
            seed.close()
        }

        val driver = JvmRuntime.openDriver(path)

        val queries = RecDatabase(driver).recQueries
        val recording = assertNotNull(queries.selectRecordingById("01J9REC").executeAsOneOrNull())
        assertNull(recording.remote_pending)
        assertEquals("folder-1", recording.drive_folder_id)
        assertEquals(0L, queries.countRemoteInFlight().executeAsOne())
        queries.updateRemotePending("transcribe", "01J9REC")
        assertEquals(1L, queries.countRemoteInFlight().executeAsOne())
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /**
     * The upgrade before it: the two Drive-id columns of docs/03 "다른 기기의 녹음". A row that
     * was already here — a recording this device made — has neither, and reads back as its own.
     */
    @Test
    fun `a version-3 database gains the Drive columns and keeps its recordings as its own`() {
        val path = tempDatabase()
        version1(path, stamped = false)
        JdbcSqliteDriver("jdbc:sqlite:$path").also { seed ->
            seed.execute(null, "DROP TABLE IF EXISTS secret_sync", 0)
            seed.execute(
                null,
                "INSERT INTO recording(id, source, platform, workflow_id, title, started_at, ended_at, duration_sec, " +
                    "timezone, dir, meta_json, status) VALUES ('01J9REC', 'desktop', 'macos', NULL, NULL, " +
                    "'2026-08-26T01:00:00.000Z', NULL, NULL, 'Asia/Seoul', '/data/recordings/x', '{}', 'finalized')",
                0,
            )
            seed.execute(
                null,
                "INSERT INTO part(recording_id, part, track, file, bytes, sha256, md5, deleted) " +
                    "VALUES ('01J9REC', 1, 'mono', 'x_p001_mono.m4a', 10, 'abc', NULL, 0)",
                0,
            )
            seed.execute(null, "PRAGMA user_version = 3", 0)
            seed.close()
        }

        val driver = JvmRuntime.openDriver(path)

        val queries = RecDatabase(driver).recQueries
        val recording = assertNotNull(queries.selectRecordingById("01J9REC").executeAsOneOrNull())
        assertNull(recording.drive_folder_id)
        assertEquals(0L, recording.remote)
        assertNull(queries.selectPartsByRecording("01J9REC").executeAsList().single().drive_file_id)
        assertEquals(emptyList(), queries.selectAdoptedRecordings().executeAsList())
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    @Test
    fun `a version-1 database is upgraded in place and keeps its rows`() {
        val path = tempDatabase()
        version1(path, stamped = true)

        val driver = JvmRuntime.openDriver(path)

        // The row written before the upgrade is still the row.
        val job = assertNotNull(RecDatabase(driver).recQueries.selectJobById(JOB).executeAsOneOrNull())
        assertEquals("01J9REC", job.recording_id)
        assertEquals("PENDING", job.status)
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /**
     * The desktop wrote its file with `Schema.create` and never stamped a version, so an installed
     * database says `user_version = 0` while holding the whole version-1 schema. Reading that as
     * "new" would run `CREATE TABLE` over tables that are already there.
     */
    @Test
    fun `a database written before the version was stamped is upgraded, not recreated`() {
        val path = tempDatabase()
        version1(path, stamped = false)

        val driver = JvmRuntime.openDriver(path)

        assertNotNull(RecDatabase(driver).recQueries.selectJobById(JOB).executeAsOneOrNull())
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /**
     * Version 1 is not one schema. The builds between M7-L4a and its second review round wrote
     * `secret_sync` while the schema version was still 1, so those databases are stamped 1 *and*
     * have the table — and a plain `CREATE TABLE` in `1.sqm` would fail the upgrade for exactly the
     * devices it exists to rescue. `2.sqm` then drops the table again, whichever way it got there.
     */
    @Test
    fun `a version-1 database that already has the secret table migrates anyway`() {
        val path = tempDatabase()
        version1(path, stamped = true, withSecretSync = true)

        val driver = JvmRuntime.openDriver(path)

        assertNotNull(RecDatabase(driver).recQueries.selectJobById(JOB).executeAsOneOrNull())
        assertFalse(hasTable(driver, "secret_sync"))
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /**
     * The upgrade this release is: a version-2 device has `secret_sync` and the `sync_state` rows
     * that pointed at Drive. Both go; the document and the device-default pointer stay, because
     * they are the user's own configuration of this device and nothing can fetch them back.
     */
    @Test
    fun `a version-2 database loses the secret table and the Drive rows, and keeps its workflows`() {
        val path = tempDatabase()
        version1(path, stamped = false, withSecretSync = true)
        JdbcSqliteDriver("jdbc:sqlite:$path").also { seed ->
            SYNC_STATE_AT_2.forEach { (key, value) ->
                seed.execute(null, "INSERT INTO sync_state(key, value) VALUES ('$key', '$value')", 0)
            }
            seed.execute(null, "PRAGMA user_version = 2", 0)
            seed.close()
        }

        val driver = JvmRuntime.openDriver(path)

        val queries = RecDatabase(driver).recQueries
        assertEquals(DOCUMENT, queries.syncGet(WorkflowStore.LOCAL_DOC).executeAsOneOrNull())
        assertEquals("01J9WF", queries.syncGet("deviceDefaultWorkflowId").executeAsOneOrNull())
        listOf("remoteFileId", "dirty", "dirtySince", "writeFrozen", "seededHere", "guessedStarterId", "secretsRemoteFileId")
            .forEach { assertNull(queries.syncGet(it).executeAsOneOrNull(), "sync_state row '$it' survived") }
        assertFalse(hasTable(driver, "secret_sync"))
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    @Test
    fun `a new file gets the whole schema and the version that goes with it`() {
        val path = tempDatabase()

        val driver = JvmRuntime.openDriver(path)

        val queries = RecDatabase(driver).recQueries
        queries.syncSet(WorkflowStore.LOCAL_DOC, DOCUMENT)
        assertEquals(DOCUMENT, queries.syncGet(WorkflowStore.LOCAL_DOC).executeAsOneOrNull())
        assertFalse(hasTable(driver, "secret_sync"))
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /** Opening a database that is already current must not migrate it again. */
    @Test
    fun `an up-to-date database is opened without being touched`() {
        val path = tempDatabase()
        JvmRuntime.openDriver(path).also { first ->
            RecDatabase(first).recQueries.syncSet(WorkflowStore.LOCAL_DOC, DOCUMENT)
            first.close()
        }

        val driver = JvmRuntime.openDriver(path)

        val queries = RecDatabase(driver).recQueries
        assertEquals(DOCUMENT, queries.syncGet(WorkflowStore.LOCAL_DOC).executeAsOneOrNull(), "a second create would have thrown")
        assertEquals(5L, userVersion(driver))
        driver.close()
    }

    /** `Rec.sq` as it stood at version 1: everything but `secret_sync`, plus one job to lose. */
    private fun version1(path: String, stamped: Boolean, withSecretSync: Boolean = false) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        VERSION_1.forEach { driver.execute(null, it, 0) }
        driver.execute(
            null,
            "INSERT INTO job(id, recording_id, workflow_id, workflow_json, status, created_at, updated_at, next_run_at) " +
                "VALUES ('$JOB', '01J9REC', '01J9WF', '{}', 'PENDING', '2026-08-26T01:00:00.000Z', " +
                "'2026-08-26T01:00:00.000Z', NULL)",
            0,
        )
        if (withSecretSync) {
            driver.execute(null, SECRET_SYNC, 0)
            driver.execute(
                null,
                "INSERT INTO secret_sync(name, updated_at, deleted, dirty) " +
                    "VALUES ('clova_key', '2026-08-29T03:00:00.000Z', 0, 1)",
                0,
            )
        }
        if (stamped) driver.execute(null, "PRAGMA user_version = 1", 0)
        driver.close()
        assertTrue(Files.exists(java.io.File(path).toPath()))
    }

    private fun hasTable(driver: SqlDriver, name: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '$name'",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) > 0 else false) },
        parameters = 0,
    ).value

    private fun userVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

    private fun tempDatabase(): String =
        Files.createTempDirectory("recly-migration").toFile().also { it.deleteOnExit() }
            .resolve("rec.db").absolutePath

    private companion object {
        const val JOB = "01J9JOB"

        /** Stands in for a real document: this test is about the row surviving, not its contents. */
        const val DOCUMENT = "{\"schema\":3}"

        /** What a version-2 device had in `sync_state` — the local two, and the Drive bookkeeping. */
        val SYNC_STATE_AT_2 = listOf(
            WorkflowStore.LOCAL_DOC to DOCUMENT,
            "deviceDefaultWorkflowId" to "01J9WF",
            "guessedStarterId" to "01J9WF",
            "remoteFileId" to "drive-1",
            "remoteVersion" to "7",
            "remoteRevisionId" to "drive-1-r3",
            "dirty" to "true",
            "dirtySince" to "2026-08-29T03:00:00.000Z",
            "mergeBase" to "2026-08-29T02:00:00.000Z",
            "lastPulledAt" to "2026-08-29T02:30:00.000Z",
            "writeFrozen" to "false",
            "seededHere" to "true",
            "secretsRemoteFileId" to "drive-2",
            "secretsRemoteVersion" to "3",
            "secretsRemoteRevisionId" to "drive-2-r1",
            "secretsLastSyncedAt" to "2026-08-29T02:45:00.000Z",
            "secretsWriteFrozen" to "false",
        )

        /**
         * The DDL of version 1, written out rather than generated: a migration is only worth
         * anything if what it is tested against is the schema as it really was, not the schema as
         * `Rec.sq` describes it today.
         */
        val VERSION_1 = listOf(
            """
            CREATE TABLE recording (
              id TEXT PRIMARY KEY, source TEXT NOT NULL, platform TEXT NOT NULL,
              workflow_id TEXT, title TEXT, started_at TEXT NOT NULL, ended_at TEXT, duration_sec REAL,
              timezone TEXT NOT NULL, dir TEXT NOT NULL, meta_json TEXT NOT NULL, status TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE part (
              recording_id TEXT NOT NULL, part INTEGER NOT NULL, track TEXT NOT NULL,
              file TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, md5 TEXT,
              deleted INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (recording_id, part, track)
            )
            """.trimIndent(),
            """
            CREATE TABLE job (
              id TEXT PRIMARY KEY, recording_id TEXT NOT NULL, workflow_id TEXT NOT NULL,
              workflow_json TEXT NOT NULL,
              status TEXT NOT NULL,
              created_at TEXT NOT NULL, updated_at TEXT NOT NULL, next_run_at TEXT,
              UNIQUE (recording_id, workflow_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE step_run (
              id TEXT PRIMARY KEY, job_id TEXT NOT NULL, step_id TEXT NOT NULL, ordinal INTEGER NOT NULL,
              status TEXT NOT NULL,
              attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TEXT, last_error TEXT,
              state_json TEXT,
              output_json TEXT,
              UNIQUE (job_id, step_id)
            )
            """.trimIndent(),
            "CREATE TABLE drive_folder_cache (path TEXT PRIMARY KEY, folder_id TEXT NOT NULL, checked_at TEXT NOT NULL)",
            "CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )

        /** What `3.sqm` added, so that [VERSION_1] plus these is a version-4 database. */
        val VERSION_4 = listOf(
            "ALTER TABLE recording ADD COLUMN drive_folder_id TEXT",
            "ALTER TABLE recording ADD COLUMN remote INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE part ADD COLUMN drive_file_id TEXT",
        )

        /** What version 2 added and version 3 takes away again. */
        val SECRET_SYNC = """
            CREATE TABLE secret_sync (
              name TEXT PRIMARY KEY, updated_at TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()
    }
}
