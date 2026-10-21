package mk.ry.redollars.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class BangumiApiTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putLong" -> { values[args!![0] as String] = args[1]; editor }
                "remove" -> { values.remove(args!![0]); editor }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString", "getLong" -> values[args!![0]] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    private fun api(code: Int = 200, body: String, inspect: (Request) -> Unit = {}): BangumiApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        return BangumiApi(client, preferences()).apply { captureCookies("chii_auth=secret; chii_sec_id=x", "browser-agent") }
    }

    @Test fun sendsEncodedFormAndOnlyWebsiteCredentials() = runBlocking {
        val api = api(body = """{"status":"ok"}""") { request ->
            assertEquals("${Config.DOLLARS_URL}?ajax=1", request.url.toString())
            assertEquals("chii_auth=secret; chii_sec_id=x", request.header("Cookie"))
            assertNull(request.header("Authorization"))
            assertEquals(Config.BGM_HOST, request.header("Origin"))
            assertEquals(Config.DOLLARS_URL, request.header("Referer"))
            assertEquals("browser-agent", request.header("User-Agent"))
            assertEquals("message=a%26b%3D%E4%B8%AD", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        }
        assertEquals(PostResult.Sent, api.post("a&b=中"))
    }

    @Test fun rejectsHtmlAndBusinessErrorsEvenWithHttp200() = runBlocking {
        for (body in listOf("<html>login</html>", """{"status":"error"}""", "{}", "garbage")) {
            assertTrue(api(body = body).post("hello") is PostResult.Failed)
        }
    }

    @Test fun distinguishesMissingSessionAnd401FromForbiddenChallenge() = runBlocking {
        assertEquals(PostResult.LoginRequired, BangumiApi(OkHttpClient(), preferences()).post("hello"))
        assertEquals(PostResult.LoginRequired, api(401, "login").post("hello"))
        assertTrue(api(403, "challenge").post("hello") is PostResult.Failed)
    }

    @Test fun restoresSessionWithoutWebViewAndClearsItOnLogout() = runBlocking {
        val api = api(body = "{}")
        val info = mk.ry.redollars.SessionInfo(42, "Alice", "")
        api.saveSession(info)
        assertEquals(info, api.restoreSession())
        api.clear()
        assertNull(api.restoreSession())
    }

    @Test fun transportFailureDoesNotReportSuccessOrRetry() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; throw IOException("offline") }.build()
        val api = BangumiApi(client, preferences()).apply { captureCookies("chii_auth=x", "agent") }
        assertTrue(api.post("hello") is PostResult.Failed)
        assertEquals(1, calls)
    }
}
