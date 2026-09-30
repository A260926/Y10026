package de.jensklingenberg.ktorfit

import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.QueryName
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The interface here mirrors the EncodedQueryNameTestApi used in the KSP source generation
 * tests one branch at a time: every URL assertion below corresponds to an exact
 * "encodedParameters.appendAll"/"parameters.appendAll" statement asserted on the
 * generated source. The TestEngine answers without a body; the Unit return type only
 * fails response conversion after the request URL was already captured, so the call is
 * wrapped in try/catch like the other engine tests in this module.
 */
interface EncodedQueryNameIntegrationApi {
    @GET("posts")
    suspend fun encodedList(@QueryName(true) names: List<String?>?)

    @GET("posts")
    suspend fun notEncodedList(@QueryName(false) names: List<String?>?)

    @GET("posts")
    suspend fun encodedArray(@QueryName(encoded = true) names: Array<String?>?)

    @GET("posts")
    suspend fun encodedScalar(@QueryName(encoded = true) name: String)
}

class QueryNameEncodedIntegrationTest {
    private fun capturedUrlOf(call: suspend EncodedQueryNameIntegrationApi.() -> Unit): String {
        var capturedUrl: String? = null
        val engine =
            object : TestEngine() {
                override fun getRequestData(data: HttpRequestData) {
                    capturedUrl = data.url.toString()
                }
            }

        val ktorfit =
            Ktorfit
                .Builder()
                .baseUrl("http://localhost/")
                .httpClient(HttpClient(engine))
                .build()

        try {
            runBlocking {
                _EncodedQueryNameIntegrationApiProvider().create(ktorfit).call()
            }
        } catch (ex: Exception) {
        }

        return requireNotNull(capturedUrl)
    }

    @Test
    fun encodedListWithPreEncodedNamesIsNotEncodedAgain() {
        // "%2B" must survive as "%2B", a literal "+" must stay a literal "+".
        val url =
            capturedUrlOf {
                encodedList(listOf("a%2Bb", "c+d"))
            }
        assertEquals("http://localhost/posts?a%2Bb&c+d", url)
    }

    @Test
    fun encodedListWithPercentIsNotEncodedAgain() {
        // The value that exposed the double encoding: "%" -> "%25" -> "%2525".
        val url =
            capturedUrlOf {
                encodedList(listOf("100%25done"))
            }
        assertEquals("http://localhost/posts?100%25done", url)
    }

    @Test
    fun encodedListWithUnicodeNameIsPassedThrough() {
        // A plain (non-percent-encoded) Unicode name is a QueryName value, not a key:
        // the encoded builder does not touch it, unlike the regular parameters builder.
        val url =
            capturedUrlOf {
                encodedList(listOf("名前"))
            }
        assertEquals("http://localhost/posts?名前", url)
    }

    @Test
    fun encodedListWithEmptyStringMemberIsKept() {
        // QueryName appends a name-only parameter; an empty member still adds an entry.
        val url =
            capturedUrlOf {
                encodedList(listOf(""))
            }
        assertEquals("http://localhost/posts?", url)
    }

    @Test
    fun encodedListWithNullMembersSkipsOnlyTheNulls() {
        // filterNotNull() drops null members but keeps the encoded neighbours intact.
        val url =
            capturedUrlOf {
                encodedList(listOf(null, "a%2Bb", null))
            }
        assertEquals("http://localhost/posts?a%2Bb", url)
    }

    @Test
    fun encodedNullListAddsNoQuery() {
        // A null collection must skip the whole block instead of emitting "?".
        val url =
            capturedUrlOf {
                encodedList(null)
            }
        assertEquals("http://localhost/posts", url)
    }

    @Test
    fun encodedNullArrayAddsNoQuery() {
        val url =
            capturedUrlOf {
                encodedArray(null)
            }
        assertEquals("http://localhost/posts", url)
    }

    @Test
    fun encodedArrayWithPreEncodedNamesIsNotEncodedAgain() {
        val url =
            capturedUrlOf {
                encodedArray(arrayOf("a%2Bb", null, "c+d"))
            }
        assertEquals("http://localhost/posts?a%2Bb&c+d", url)
    }

    @Test
    fun notEncodedListStillGetsEncoded() {
        // The encoded=false path deliberately keeps re-encoding and must not change.
        val url =
            capturedUrlOf {
                notEncodedList(listOf("a%2Bb", "名前"))
            }
        assertEquals("http://localhost/posts?a%252Bb&%E5%90%8D%E5%89%8D", url)
    }

    @Test
    fun encodedScalarRemainsPassthrough() {
        // Scalar encoded was already on encodedParameters; it must keep behaving.
        val url =
            capturedUrlOf {
                encodedScalar("100%25done")
            }
        assertEquals("http://localhost/posts?100%25done", url)
    }
}
