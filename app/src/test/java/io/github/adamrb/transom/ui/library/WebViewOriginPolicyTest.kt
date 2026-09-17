package io.github.adamrb.transom.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library WebView's same-origin guard: only the configured server's exact
 * (scheme, host, effective port) may load inside the WebView.
 */
class WebViewOriginPolicyTest {

    private val policy = WebViewOriginPolicy("https://bridge.example.com")

    // MARK: - Allowed: same origin, any path

    @Test
    fun sameOriginRootIsAllowed() {
        assertTrue(policy.allows("https://bridge.example.com"))
        assertTrue(policy.allows("https://bridge.example.com/"))
    }

    @Test
    fun subpathsQueriesAndFragmentsAreAllowed() {
        assertTrue(policy.allows("https://bridge.example.com/recordings/42"))
        assertTrue(policy.allows("https://bridge.example.com/app?tab=files#top"))
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertTrue(policy.allows("HTTPS://BRIDGE.EXAMPLE.COM/dashboard"))
    }

    @Test
    fun explicitDefaultPortEqualsImplicit() {
        assertTrue(policy.allows("https://bridge.example.com:443/x"))
        assertTrue(WebViewOriginPolicy("https://h.example:443").allows("https://h.example/y"))
    }

    @Test
    fun configuredNonDefaultPortMatchesItself() {
        val p = WebViewOriginPolicy("https://h.example:8443/base")
        assertTrue(p.allows("https://h.example:8443/other/path"))
    }

    // MARK: - Denied: different origin

    @Test
    fun differentHostIsDenied() {
        assertFalse(policy.allows("https://evil.example.com/"))
        assertFalse(policy.allows("https://bridge.example.com.evil.net/"))
    }

    @Test
    fun subdomainIsADifferentHost() {
        assertFalse(policy.allows("https://api.bridge.example.com/"))
    }

    @Test
    fun schemeDowngradeIsDenied() {
        assertFalse(policy.allows("http://bridge.example.com/"))
    }

    @Test
    fun differentPortIsDenied() {
        assertFalse(policy.allows("https://bridge.example.com:8443/"))
        val p = WebViewOriginPolicy("https://h.example:8443")
        assertFalse(p.allows("https://h.example/"))
        assertFalse(p.allows("https://h.example:9443/"))
    }

    @Test
    fun userinfoLookalikeIsDenied() {
        // The real host here is evil.com — URI.host must not be fooled by userinfo
        assertFalse(policy.allows("https://bridge.example.com@evil.com/"))
    }

    @Test
    fun nonHttpSchemesAreDenied() {
        assertFalse(policy.allows("javascript:alert(1)"))
        assertFalse(policy.allows("data:text/html,<h1>x</h1>"))
        assertFalse(policy.allows("file:///etc/hosts"))
        assertFalse(policy.allows("about:blank"))
        assertFalse(policy.allows("intent://bridge.example.com#Intent;end"))
    }

    @Test
    fun garbageAndBlankAreDenied() {
        assertFalse(policy.allows(null))
        assertFalse(policy.allows(""))
        assertFalse(policy.allows("not a url at all ::"))
        assertFalse(policy.allows("/relative/path"))
    }

    // MARK: - Unconfigured policy denies everything

    @Test
    fun nullOrInvalidServerUrlDeniesEverything() {
        assertFalse(WebViewOriginPolicy(null).allows("https://bridge.example.com/"))
        assertFalse(WebViewOriginPolicy("").allows("https://bridge.example.com/"))
        assertFalse(WebViewOriginPolicy("nonsense").allows("https://bridge.example.com/"))
    }

    @Test
    fun serverUrlWithSubpathStillMatchesWholeOrigin() {
        // Origin policy is origin-level: a base URL with a path allows sibling paths too
        val p = WebViewOriginPolicy("https://h.example/bridge")
        assertTrue(p.allows("https://h.example/other"))
    }
}
