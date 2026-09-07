package org.plaudbridge.app.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Self-update checks: version comparison against the server manifest, bounded downloads,
 * MANDATORY sha256/size verification, APK identity rules, and the success/failure auto-check
 * throttle. The PackageInstaller session itself stays untested (thin plumbing around the
 * verified file).
 */
@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RecordingStore.init(context)
        RecordingStore.clearAll()
        server = MockWebServer()
        server.start()
        RecordingStore.serverBaseUrl = server.url("/").toString().trimEnd('/')
        RecordingStore.serverAuthToken = "test-token"
    }

    @After
    fun tearDown() {
        server.shutdown()
        UpdateManager.apkDir(context).deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun infoJson(versionCode: Int, sha256: String = "ab".repeat(32), size: Long = 3): String =
        """{"version_code":$versionCode,"version_name":"9.9.9","filename":"app.apk",""" +
            """"sha256":"$sha256","size_bytes":$size,"uploaded_at":"2026-09-06T00:00:00Z","notes":"fixes"}"""

    // MARK: - checkForUpdate (MockWebServer)

    @Test
    fun newerVersionCodeIsAnUpdate() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson(versionCode = 3)))
        val result = UpdateManager.checkForUpdate(installedVersionCode = 2)
        assertTrue(result is UpdateManager.CheckResult.UpdateAvailable)
        val manifest = (result as UpdateManager.CheckResult.UpdateAvailable).manifest
        assertEquals(3, manifest.versionCode)
        assertEquals("9.9.9", manifest.versionName)
        assertEquals("fixes", manifest.notes)
        assertEquals("Bearer test-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun equalVersionCodeIsUpToDate() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson(versionCode = 2)))
        assertEquals(
            UpdateManager.CheckResult.UpToDate,
            UpdateManager.checkForUpdate(installedVersionCode = 2)
        )
    }

    @Test
    fun lowerVersionCodeIsUpToDateNotADowngrade() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(infoJson(versionCode = 1)))
        assertEquals(
            UpdateManager.CheckResult.UpToDate,
            UpdateManager.checkForUpdate(installedVersionCode = 2)
        )
    }

    @Test
    fun http404MeansNotHosted() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"no apk"}"""))
        assertEquals(
            UpdateManager.CheckResult.NotHosted,
            UpdateManager.checkForUpdate(installedVersionCode = 2)
        )
    }

    @Test
    fun http500IsAnError() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(
            UpdateManager.checkForUpdate(installedVersionCode = 2)
                is UpdateManager.CheckResult.Error
        )
    }

    @Test
    fun malformedJsonIsAnError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertTrue(
            UpdateManager.checkForUpdate(installedVersionCode = 2)
                is UpdateManager.CheckResult.Error
        )
    }

    @Test
    fun stringVersionCodeFailsStrictParsing() {
        val body = """{"version_code":"3","version_name":"9.9.9","filename":"a.apk",""" +
            """"sha256":"${"ab".repeat(32)}","size_bytes":3,"uploaded_at":null,"notes":null}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        assertTrue(
            UpdateManager.checkForUpdate(installedVersionCode = 2)
                is UpdateManager.CheckResult.Error
        )
    }

    // MARK: - Manifest.fromJson strictness

    @Test
    fun manifestParsesWithNullNotes() {
        val m = UpdateManager.Manifest.fromJson(
            """{"version_code":3,"version_name":"0.3.0","filename":"a.apk",""" +
                """"sha256":"${"cd".repeat(32)}","size_bytes":1234,"uploaded_at":null,"notes":null}"""
        )
        assertNotNull(m)
        assertNull(m!!.notes)
        assertNull(m.uploadedAt)
        assertEquals(1234L, m.sizeBytes)
    }

    @Test
    fun manifestRejectsMissingOrInvalidFields() {
        val sha = "ab".repeat(32)
        // missing sha256
        assertNull(
            UpdateManager.Manifest.fromJson(
                """{"version_code":3,"version_name":"x","filename":"a.apk","size_bytes":3}"""
            )
        )
        // sha256 not 64 hex chars
        assertNull(
            UpdateManager.Manifest.fromJson(
                """{"version_code":3,"version_name":"x","filename":"a.apk","sha256":"zz","size_bytes":3}"""
            )
        )
        // non-positive size
        assertNull(
            UpdateManager.Manifest.fromJson(
                """{"version_code":3,"version_name":"x","filename":"a.apk","sha256":"$sha","size_bytes":0}"""
            )
        )
        // blank version_name
        assertNull(
            UpdateManager.Manifest.fromJson(
                """{"version_code":3,"version_name":"","filename":"a.apk","sha256":"$sha","size_bytes":3}"""
            )
        )
    }

    // MARK: - sha256 verification

    @Test
    fun verifyAcceptsMatchingFile() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val f = File.createTempFile("apk", ".apk").apply { writeBytes(bytes) }
        assertNull(UpdateManager.verify(f, sha256Hex(bytes), bytes.size.toLong()))
        // Case-insensitive hash comparison
        assertNull(UpdateManager.verify(f, sha256Hex(bytes).uppercase(), bytes.size.toLong()))
        f.delete()
    }

    @Test
    fun verifyRejectsTamperedBytesAndWrongSize() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val expected = sha256Hex(bytes)
        val tampered = File.createTempFile("apk", ".apk").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 9))
        }
        assertNotNull(UpdateManager.verify(tampered, expected, 5L)) // same size, different bytes
        assertNotNull(UpdateManager.verify(tampered, expected, 99L)) // wrong size
        tampered.delete()
    }

    private fun manifest(
        sha256: String,
        sizeBytes: Long,
        versionCode: Int = 3
    ): UpdateManager.Manifest = UpdateManager.Manifest(
        versionCode = versionCode, versionName = "0.3.0", filename = "a.apk",
        sha256 = sha256, sizeBytes = sizeBytes, uploadedAt = null, notes = null
    )

    /** No leftover final or temp (.part) files in the download dir. */
    private fun assertDownloadDirEmpty() {
        val leftovers = UpdateManager.apkDir(context).listFiles()?.map { it.name } ?: emptyList()
        assertTrue("expected no leftover files, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun downloadAndVerifyReturnsVerifiedFile() {
        val apkBytes = ByteArray(1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
        val file = UpdateManager.downloadAndVerify(
            context, manifest(sha256Hex(apkBytes), apkBytes.size.toLong())
        ) { null } // identity checked separately; see validateIdentityCore tests
        assertTrue(file.exists())
        assertEquals(apkBytes.size.toLong(), file.length())
        assertEquals("update-3.apk", file.name)
        assertEquals(
            "temp .part file must be renamed away",
            listOf("update-3.apk"),
            UpdateManager.apkDir(context).listFiles()?.map { it.name }
        )
    }

    @Test
    fun downloadAndVerifyDeletesFileOnHashMismatch() {
        val apkBytes = ByteArray(64) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
        var thrown: Exception? = null
        try {
            UpdateManager.downloadAndVerify(
                context, manifest("ab".repeat(32), apkBytes.size.toLong())
            ) { null }
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull("verification must throw on hash mismatch", thrown)
        assertDownloadDirEmpty()
    }

    @Test
    fun downloadAndVerifyDeletesFileOnIdentityFailure() {
        val apkBytes = ByteArray(64) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
        var thrown: IOException? = null
        try {
            UpdateManager.downloadAndVerify(
                context, manifest(sha256Hex(apkBytes), apkBytes.size.toLong())
            ) { "apk package 'evil' is not ours" }
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull("identity failure must throw", thrown)
        assertTrue(thrown!!.message!!.contains("evil"))
        assertDownloadDirEmpty()
    }

    // MARK: - Bounded download

    @Test
    fun downloadAbortsOnContentLengthMismatch() {
        // Server advertises (and would send) 64 bytes; the manifest says 10.
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(64))))
        var thrown: Exception? = null
        try {
            UpdateManager.downloadAndVerify(context, manifest("ab".repeat(32), 10)) { null }
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("content-length mismatch must abort", thrown)
        assertTrue(thrown!!.message!!.contains("Content-Length"))
        assertDownloadDirEmpty()
    }

    @Test
    fun downloadAbortsWhenChunkedStreamExceedsManifestSize() {
        // Chunked response (no Content-Length): the byte counter must abort past size_bytes.
        server.enqueue(
            MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(ByteArray(4096)), 1024)
        )
        var thrown: Exception? = null
        try {
            UpdateManager.downloadAndVerify(context, manifest("ab".repeat(32), 100)) { null }
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull("oversized stream must abort", thrown)
        assertTrue(thrown!!.message!!.contains("exceeded"))
        assertDownloadDirEmpty()
    }

    @Test
    fun oversizedManifestIsRefusedBeforeAnyRequest() {
        var thrown: Exception? = null
        try {
            UpdateManager.downloadAndVerify(
                context, manifest("ab".repeat(32), UpdateManager.MAX_APK_BYTES + 1)
            ) { null }
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals("no request may be made for an oversized apk", 0, server.requestCount)
        val hugeInfo = infoJson(versionCode = 99, size = UpdateManager.MAX_APK_BYTES + 1)
        server.enqueue(MockResponse().setResponseCode(200).setBody(hugeInfo))
        assertTrue(
            "oversized hosted apk must never be offered",
            UpdateManager.checkForUpdate(2) is UpdateManager.CheckResult.Error
        )
    }

    // MARK: - APK identity rules (pure core; the PackageManager wrapper is thin)

    private val signerA = "aa".repeat(32)
    private val signerB = "bb".repeat(32)

    private fun identity(
        archivePackage: String? = "org.plaudbridge.app",
        archiveVersion: Long = 3,
        installedVersion: Long = 2,
        archiveSigners: Set<String> = setOf(signerA),
        installedLineage: Set<String> = setOf(signerA),
        parsed: Boolean = true
    ): String? = UpdateManager.validateIdentityCore(
        expectedPackage = "org.plaudbridge.app",
        archivePackage = archivePackage,
        archiveVersionCode = archiveVersion,
        installedVersionCode = installedVersion,
        archiveSigners = archiveSigners,
        installedLineage = installedLineage,
        archiveParsed = parsed
    )

    @Test
    fun identityAcceptsMatchingNewerApk() {
        assertNull(identity())
        // Rotated lineage: archive signed with a cert from the installed history
        assertNull(identity(archiveSigners = setOf(signerA), installedLineage = setOf(signerA, signerB)))
    }

    @Test
    fun identityRejectsForeignPackage() {
        assertNotNull(identity(archivePackage = "com.evil.lookalike"))
        assertNotNull(identity(archivePackage = null))
    }

    @Test
    fun identityRejectsNonNewerVersion() {
        assertNotNull("same version", identity(archiveVersion = 2))
        assertNotNull("downgrade", identity(archiveVersion = 1))
    }

    @Test
    fun identityRejectsSignerMismatchOrMissing() {
        assertNotNull("unknown signer", identity(archiveSigners = setOf(signerB)))
        assertNotNull("no archive signers", identity(archiveSigners = emptySet()))
        assertNotNull("no installed lineage", identity(installedLineage = emptySet()))
        assertNotNull(
            "extra signer not in lineage",
            identity(archiveSigners = setOf(signerA, signerB), installedLineage = setOf(signerA))
        )
    }

    @Test
    fun identityRejectsUnparseableArchive() {
        assertNotNull(identity(parsed = false))
    }

    @Test
    fun validateApkIdentityRejectsGarbageFile() {
        // End-to-end wrapper: a non-APK file cannot be parsed by PackageManager.
        val junk = File.createTempFile("junk", ".apk").apply { writeBytes(ByteArray(16) { 1 }) }
        assertNotNull(UpdateManager.validateApkIdentity(context, junk))
        junk.delete()
    }

    // MARK: - Auto-check throttle (24h success / 1h failure backoff / host reset)

    @Test
    fun autoCheckThrottle() {
        val now = 1_000_000_000_000L
        val host = "bridge.example.com"
        fun due(success: Long = 0, failure: Long = 0, lastHost: String? = host, current: String? = host) =
            UpdateManager.isAutoCheckDue(success, failure, now, lastHost, current)

        assertTrue("never checked -> due", due())
        assertTrue("success 25h ago -> due", due(success = now - 25 * 60 * 60 * 1000L))
        assertTrue("success exactly 24h ago -> due", due(success = now - UpdateManager.CHECK_INTERVAL_MS))
        assertFalse("success 1h ago -> not due", due(success = now - 60 * 60 * 1000L))
        assertTrue("future success timestamp (clock rollback) -> due", due(success = now + 60 * 60 * 1000L))

        assertFalse("failure 10min ago -> backoff blocks", due(failure = now - 10 * 60 * 1000L))
        assertTrue("failure 61min ago -> due again", due(failure = now - 61 * 60 * 1000L))
        assertTrue("future failure timestamp -> due", due(failure = now + 60 * 1000L))

        assertTrue(
            "host change resets the throttle",
            due(success = now - 1000L, lastHost = "old.example.com", current = host)
        )
    }

    @Test
    fun autoCheckInFlightGuardIsExclusive() {
        assertTrue(UpdateManager.tryBeginAutoCheck())
        assertFalse("second concurrent check refused", UpdateManager.tryBeginAutoCheck())
        UpdateManager.endAutoCheck()
        assertTrue("free again after end", UpdateManager.tryBeginAutoCheck())
        UpdateManager.endAutoCheck()
    }

    // MARK: - stale download cleanup

    @Test
    fun cleanupDeletesCurrentOlderAndUnknownApks() {
        val dir = UpdateManager.apkDir(context)
        val now = System.currentTimeMillis()
        val current = File(dir, "update-2.apk").apply { writeBytes(byteArrayOf(1)) }
        val older = File(dir, "update-1.apk").apply { writeBytes(byteArrayOf(1)) }
        val unknown = File(dir, "garbage.bin").apply { writeBytes(byteArrayOf(1)) }
        val newer = File(dir, "update-3.apk").apply { writeBytes(byteArrayOf(1)) }
        val expired = File(dir, "update-4.apk").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now - UpdateManager.APK_MAX_AGE_MS - 1000)
        }

        UpdateManager.cleanupStaleApks(context, installedVersionCode = 2, nowMs = now)

        assertFalse(current.exists())
        assertFalse(older.exists())
        assertFalse(unknown.exists())
        assertFalse(expired.exists())
        assertTrue("fresh newer download survives", newer.exists())
    }
}
