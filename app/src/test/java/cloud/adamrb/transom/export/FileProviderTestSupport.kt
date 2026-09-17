package cloud.adamrb.transom.export

import androidx.core.content.FileProvider

/**
 * FileProvider caches its PathStrategy (the resolved root directories) in a static map keyed by
 * authority. Robolectric hands every test method a fresh temp cacheDir but keeps library statics
 * within a sandbox, so after the first getUriForFile in a JVM every later test would fail with
 * "Failed to find configured root". Clearing the cache before each test restores isolation.
 */
object FileProviderTestSupport {
    fun resetCache() {
        val field = FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
