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
 * Self-update checks: version comparison against the server manifest, MANDATORY sha256/size
 * verification before install, and the 24h auto-check throttle. The installer intent itself
 * stays untested (thin plumbing around the verified file).
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

    @Test
    fun downloadAndVerifyReturnsVerifiedFile() {
        val apkBytes = ByteArray(1024) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
        val manifest = UpdateManager.Manifest(
            versionCode = 3, versionName = "0.3.0", filename = "a.apk",
            sha256 = sha256Hex(apkBytes), sizeBytes = apkBytes.size.toLong(),
            uploadedAt = null, notes = null
        )
        val file = UpdateManager.downloadAndVerify(context, manifest)
        assertTrue(file.exists())
        assertEquals(apkBytes.size.toLong(), file.length())
        assertEquals("update-3.apk", file.name)
    }

    @Test
    fun downloadAndVerifyDeletesFileOnHashMismatch() {
        val apkBytes = ByteArray(64) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))
        val manifest = UpdateManager.Manifest(
            versionCode = 3, versionName = "0.3.0", filename = "a.apk",
            sha256 = "ab".repeat(32), // wrong hash
            sizeBytes = apkBytes.size.toLong(), uploadedAt = null, notes = null
        )
        var thrown: Exception? = null
        try {
            UpdateManager.downloadAndVerify(context, manifest)
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull("verification must throw on hash mismatch", thrown)
        assertFalse(
            "unverified apk must be deleted",
            File(UpdateManager.apkDir(context), "update-3.apk").exists()
        )
    }

    // MARK: - 24h auto-check throttle

    @Test
    fun autoCheckThrottle() {
        val now = 1_000_000_000_000L
        assertTrue("never checked -> due", UpdateManager.isAutoCheckDue(0L, now))
        assertTrue(
            "25h ago -> due",
            UpdateManager.isAutoCheckDue(now - 25 * 60 * 60 * 1000L, now)
        )
        assertTrue(
            "exactly 24h ago -> due",
            UpdateManager.isAutoCheckDue(now - UpdateManager.CHECK_INTERVAL_MS, now)
        )
        assertFalse(
            "1h ago -> not due",
            UpdateManager.isAutoCheckDue(now - 60 * 60 * 1000L, now)
        )
        assertTrue(
            "future timestamp (clock rollback) -> due, never wedged",
            UpdateManager.isAutoCheckDue(now + 60 * 60 * 1000L, now)
        )
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
