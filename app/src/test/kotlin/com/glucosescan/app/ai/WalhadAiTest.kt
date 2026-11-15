package com.glucosescan.app.ai

import com.glucosescan.app.core.AdvisorBlock
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.testing.FakeServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private fun blocks(): List<AdvisorBlock> = listOf(
    AdvisorBlock("Time in range", listOf("61 % of the time.")),
    AdvisorBlock("Lows", listOf("2 lows.", "The longest lasted 35 minutes."))
)

private fun question(
    history: List<AiTurn> = emptyList(),
    report: String = "Mean 154 mg/dL.",
    q: String = "What do you see?"
) = AiQuestion(
    report = report,
    question = q,
    language = AnswerLanguage.ENGLISH,
    history = history,
    fallback = blocks()
)

/**
 * The AI that is built in, and the promise it keeps: whenever the AI cannot answer, the phone
 * answers instead, says so, and never passes its own words off as the AI's.
 */
@RunWith(RobolectricTestRunner::class)
class WalhadAiTest {
    private lateinit var server: FakeServer

    private val url = "https://example.supabase.co/functions/v1/walhad-ai"
    private val secret = "s3cret"

    @Before
    fun setUp() {
        server = FakeServer()
    }

    private fun ai(install: String = "") = WalhadAi(server, url, secret, install)

    private fun answers(body: String, code: Int = 200) {
        server.answer = { HttpResponse(code, body) }
    }

    private fun ok(answer: String = "Your nights are steady.") =
        answers("""{"ok":true,"answer":"$answer","model":"gemini-2.5-flash","source":"ai"}""")

    private fun fails(problem: String, detail: String = "") =
        answers("""{"ok":false,"problem":"$problem","detail":"$detail"}""")

    /** What the app sent, as JSON. */
    private fun sent(): JSONObject = JSONObject(server.last.body!!)

    // ------------------------------------------------------------- is it built in

    @Test fun `no address means the phone answers`() {
        assertFalse(WalhadAi(server, "", "").isBuiltIn)
        assertFalse(WalhadAi(server, url, "").isBuiltIn)
        assertFalse(WalhadAi(server, "", secret).isBuiltIn)
        // a plain http address is never used: the app only ever talks https
        assertFalse(WalhadAi(server, "http://example.com/f", secret).isBuiltIn)
    }

    @Test fun `an https address and a secret mean it is built in`() {
        assertTrue(ai().isBuiltIn)
    }

    // ------------------------------------------------------------- the good path

    @Test fun `the AI answers and says it is the AI`() {
        ok()
        val answer = ai().ask(question())
        assertTrue(answer.isOk)
        assertEquals("Your nights are steady.", answer.text)
        assertEquals(AiSource.AI, answer.source)
        assertEquals(null, answer.problem)
    }

    @Test fun `the report, the question and the language are sent`() {
        ok()
        ai().ask(
            AiQuestion(
                report = "Mean 154 mg/dL.",
                question = "and at night?",
                language = AnswerLanguage.SOMALI,
                fallback = blocks()
            )
        )
        assertEquals("Mean 154 mg/dL.", sent().getString("report"))
        assertEquals("and at night?", sent().getString("question"))
        assertEquals("so", sent().getString("language"))
        assertEquals(secret, server.last.headers["x-walhad-secret"])
        assertEquals(url, server.last.url)
        assertEquals("POST", server.last.method)
    }

    @Test fun `the install id is sent so the server can count questions`() {
        ok()
        ai(install = "abc123").ask(question())
        assertEquals("abc123", sent().getString("install"))
    }

    @Test fun `only the last six exchanges are sent`() {
        ok()
        ai().ask(question(history = (1..10).map { AiTurn("q$it", "a$it") }))
        val history = sent().getJSONArray("history")
        assertEquals(6, history.length())
        assertEquals("q5", history.getJSONObject(0).getString("q"))
        assertEquals("q10", history.getJSONObject(5).getString("q"))
    }

    @Test fun `a very long question is cut off before it is sent`() {
        ok()
        ai().ask(question(q = "x".repeat(5000)))
        assertEquals(500, sent().getString("question").length)
    }

    // ------------------------------------------------------------- failing safe

    @Test fun `no internet still gives the person an answer, from the phone`() {
        server.offline = true
        val answer = ai().ask(question())
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.NO_INTERNET, answer.problem)
        assertTrue(answer.text.contains("Time in range"))
        assertFalse(answer.isOk)
    }

    @Test fun `no address at all answers from the phone without complaining`() {
        val answer = WalhadAi(server, "", "").ask(question())
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(null, answer.problem)
        assertTrue(answer.text.contains("Lows"))
    }

    @Test fun `no numbers to send answers from the phone`() {
        ok()
        val answer = ai().ask(question(report = ""))
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(null, answer.problem)
    }

    @Test fun `the daily limit answers from the phone and says why`() {
        fails("limit", "daily limit of 20 questions reached")
        val answer = ai().ask(question())
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.LIMIT, answer.problem)
        assertEquals("daily limit of 20 questions reached", answer.detail)
        assertTrue(answer.text.isNotEmpty())
    }

    @Test fun `a refused secret answers from the phone and says why`() {
        fails("key", "not allowed")
        val answer = ai().ask(question())
        assertEquals(AiProblem.KEY, answer.problem)
        assertEquals(AiSource.PHONE, answer.source)
    }

    @Test fun `a blocked answer answers from the phone and says why`() {
        // The server withholds an answer that drifted into giving a dose.
        fails("blocked", "the answer was withheld by the safety check")
        val answer = ai().ask(question())
        assertEquals(AiProblem.BLOCKED, answer.problem)
        assertEquals(AiSource.PHONE, answer.source)
    }

    @Test fun `an unknown problem is treated as a server problem`() {
        fails("something-new", "huh")
        assertEquals(AiProblem.SERVER, ai().ask(question()).problem)
    }

    @Test fun `an answer that is not JSON answers from the phone`() {
        answers("<html>502 Bad Gateway</html>", code = 502)
        val answer = ai().ask(question())
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.SERVER, answer.problem)
    }

    @Test fun `an empty answer from the AI is not shown, the phone answers instead`() {
        ok("   ")
        val answer = ai().ask(question())
        assertEquals(AiSource.PHONE, answer.source)
        assertEquals(AiProblem.EMPTY, answer.problem)
        assertTrue(answer.text.isNotEmpty())
    }

    @Test fun `a very long detail is cut short`() {
        fails("server", "e".repeat(900))
        assertEquals(240, ai().ask(question()).detail.length)
    }

    // ------------------------------------------------------------- the phone's words

    @Test fun `the phone's words are one heading and its sentences per block`() {
        val text = WalhadAi.localText(blocks())
        assertEquals("Time in range\n61 % of the time.\n\nLows\n2 lows.\nThe longest lasted 35 minutes.", text)
    }

    @Test fun `no blocks means no words`() {
        assertEquals("", WalhadAi.localText(emptyList()))
    }

    /**
     * The rule the whole design hangs on: whatever happens, the answer never claims to be from
     * an AI when it is not, and the person is never left with an empty screen.
     */
    @Test fun `every failure still gives words, and none of them claims to be from an AI`() {
        val bodies = listOf(
            """{"ok":false,"problem":"limit","detail":""}""",
            """{"ok":false,"problem":"key","detail":""}""",
            """{"ok":false,"problem":"blocked","detail":""}""",
            """{"ok":false,"problem":"empty","detail":""}""",
            """{"ok":false,"problem":"server","detail":""}""",
            """{"ok":true,"answer":""}""",
            """not json at all"""
        )
        for (body in bodies) {
            server = FakeServer()
            answers(body)
            val answer = ai().ask(question())
            assertEquals("for $body", AiSource.PHONE, answer.source)
            assertTrue("for $body, the person must still get words", answer.text.isNotEmpty())
        }
    }
}
