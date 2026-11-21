package com.glucosescan.app

import com.glucosescan.app.ai.AiProblem
import com.glucosescan.app.ai.AiQuestion
import com.glucosescan.app.ai.AiSource
import com.glucosescan.app.ai.AiTurn
import com.glucosescan.app.ai.WalhadAi
import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.net.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The AI door: what the app sends, and what it does with every kind of answer.
 *
 * A phone in a meeting with no network must never be left with a blank card, and a server that
 * is over its limit must not be shown as if the AI had refused to help for no reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.TIRAMISU])
class AiDoorTest {

    private class Fake(private val reply: () -> HttpResponse) : HttpTransport {
        var last: HttpRequest? = null
        override fun send(request: HttpRequest): HttpResponse {
            last = request
            return reply()
        }
    }

    private fun json(body: String) = HttpResponse(200, body)

    private val good = """{"ok":true,"answer":"Your average is 138 mg/dL.","model":"openai/gpt-oss-120b","source":"ai"}"""

    @Test
    fun aGoodAnswerIsShownAsTheAiAnswer() {
        val transport = Fake { json(good) }
        val answer = WalhadAi(transport, "https://example.supabase.co/functions/v1/walhad-ai-core", "s3cret", "install-1")
            .ask(AiQuestion(report = "mean 138", question = "How am I doing?"))
        assertEquals(AiSource.AI, answer.source)
        assertEquals("Your average is 138 mg/dL.", answer.text)
    }

    @Test
    fun theSecretTravelsInItsOwnHeaderAndTheQuestionInTheBody() {
        val transport = Fake { json(good) }
        WalhadAi(transport, "https://example.supabase.co/functions/v1/walhad-ai-core", "s3cret", "install-9")
            .ask(AiQuestion(report = "mean 138", question = "How am I doing?", history = listOf(AiTurn("And at night?", "Lower."))))
        val request = transport.last!!
        assertEquals("POST", request.method)
        assertTrue("https only", request.url.startsWith("https://"))
        assertEquals("s3cret", request.headers["x-walhad-secret"])
        val body = request.body!!
        assertTrue(body.contains("\"report\""))
        assertTrue(body.contains("How am I doing?"))
        assertTrue("the earlier exchange is carried", body.contains("And at night?"))
        assertTrue("the install id is carried", body.contains("install-9"))
    }

    @Test
    fun noInternetFallsBackToThePhoneAndSaysWhy() {
        val transport = Fake { throw IOException("no route to host") }
        val answer = WalhadAi(transport, "https://example.supabase.co/functions/v1/x", "s3cret", "i")
            .ask(AiQuestion(report = "mean 138", fallback = emptyList()))
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.NO_INTERNET, answer.problem)
    }

    @Test
    fun theDailyLimitIsNotReportedAsABrokenPhone() {
        val transport = Fake { HttpResponse(429, """{"ok":false,"problem":"limit","detail":"daily limit"}""") }
        val answer = WalhadAi(transport, "https://example.supabase.co/functions/v1/x", "s3cret", "i")
            .ask(AiQuestion(report = "mean 138"))
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.LIMIT, answer.problem)
    }

    @Test
    fun aWrongSecretIsReportedAsAKeyProblem() {
        val transport = Fake { HttpResponse(401, """{"ok":false,"problem":"key","detail":"not allowed"}""") }
        val answer = WalhadAi(transport, "https://example.supabase.co/functions/v1/x", "wrong", "i")
            .ask(AiQuestion(report = "mean 138"))
        assertEquals(AiProblem.KEY, answer.problem)
    }

    @Test
    fun aBlankAnswerIsNotAnAnswer() {
        val transport = Fake { json("""{"ok":true,"answer":"   "}""") }
        val answer = WalhadAi(transport, "https://example.supabase.co/functions/v1/x", "s3cret", "i")
            .ask(AiQuestion(report = "mean 138"))
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.EMPTY, answer.problem)
    }

    @Test
    fun aBuildWithoutAnAddressNeverCallsTheNetwork() {
        val transport = Fake { throw AssertionError("must not be called") }
        val answer = WalhadAi(transport, "", "", "i").ask(AiQuestion(report = "mean 138"))
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(null, answer.problem)
    }

    @Test
    fun thisBuildCarriesTheLiveAddress() {
        // the address and the secret must be in the build, or the AI can never answer
        assertTrue("the AI address must be built in", BuildConfig.WALHAD_AI_URL.startsWith("https://"))
        assertTrue("the AI secret must be built in", BuildConfig.WALHAD_AI_SECRET.isNotBlank())
        assertTrue(
            "the address must be the live function",
            BuildConfig.WALHAD_AI_URL.contains("supabase.co/functions/v1/walhad-ai-core")
        )
    }
}
