package com.glucosescan.app.ai

import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.data.AiProvider
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.testing.FakeServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How the app asks an AI service, and what it makes of the answers. */
@RunWith(RobolectricTestRunner::class)
class AiClientTest {
    private lateinit var server: FakeServer
    private lateinit var client: AiClient
    private val gemini = AiConfig(AiProvider.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "KEY-123", "gemini-4-hydrogen")
    private val openAi = AiConfig(AiProvider.OPENAI, "https://api.openai.com/v1", "sk-test-456", "gpt-test")

    @Before
    fun setUp() {
        server = FakeServer()
        client = AiClient(server)
    }

    private fun answers(code: Int, body: String) {
        server.answer = { HttpResponse(code, body) }
    }

    // ------------------------------------------------------------ Google Gemini

    @Test
    fun geminiIsAskedTheWayGoogleDescribesIt() {
        answers(
            200,
            "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                "{\"text\":\"Let me look at the lows first.\",\"thought\":true}," +
                "{\"text\":\"Your glucose \"},{\"text\":\"was mostly in range.\\n\"}]},\"finishReason\":\"STOP\"}]," +
                "\"usageMetadata\":{\"totalTokenCount\":812}}"
        )
        val answer = client.ask(gemini, "THE RULES", "THE QUESTION")
        assertTrue(answer.isOk)
        assertEquals("the pieces are joined; what the model thought on the way is left out", "Your glucose was mostly in range.", answer.text)

        val request = server.last
        assertEquals("POST", request.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent", request.url)
        assertEquals("KEY-123", request.headers["x-goog-api-key"])
        assertFalse("the key travels in a header, never in the address", request.url.contains("KEY-123"))
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("an AI may think for a while", 150_000, request.readTimeoutMillis)

        val body = JSONObject(request.body!!)
        assertEquals("THE RULES", body.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents = body.getJSONArray("contents")
        assertEquals(1, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("THE QUESTION", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("nothing that only some models know", setOf("system_instruction", "contents"), body.keys().asSequence().toSet())
    }

    @Test
    fun aGeminiModelNameAsTheListWritesIt() {
        answers(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}")
        client.ask(AiConfig(AiProvider.GEMINI, gemini.url, "k", "models/gemini-3.8-flash"), "s", "u")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", server.last.url)
    }

    @Test
    fun geminiCanRefuseOrSayNothing() {
        answers(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
        var answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.BLOCKED, answer.problem)
        assertEquals("SAFETY", answer.detail)

        answers(200, "{\"candidates\":[{\"finishReason\":\"PROHIBITED_CONTENT\"}]}")
        answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.BLOCKED, answer.problem)

        answers(200, "{\"candidates\":[]}")
        assertEquals(AiProblem.EMPTY, client.ask(gemini, "s", "u").problem)
        answers(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]},\"finishReason\":\"STOP\"}]}")
        assertEquals(AiProblem.EMPTY, client.ask(gemini, "s", "u").problem)
        answers(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"all thinking\",\"thought\":true}]},\"finishReason\":\"MAX_TOKENS\"}]}")
        assertEquals("the model used up its length while thinking", AiProblem.EMPTY, client.ask(gemini, "s", "u").problem)

        answers(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Cut off in the mid\"}]},\"finishReason\":\"MAX_TOKENS\"}]}")
        assertEquals("an answer that was cut is still shown", "Cut off in the mid", client.ask(gemini, "s", "u").text)
    }

    @Test
    fun whatGeminiSaysWhenSomethingIsWrong() {
        answers(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}")
        var answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.KEY, answer.problem)
        assertEquals("HTTP 400 API key not valid. Please pass a valid API key.", answer.detail)
        assertFalse(answer.detail.contains("KEY-123"))

        answers(403, "{\"error\":{\"code\":403,\"message\":\"Permission denied\",\"status\":\"PERMISSION_DENIED\"}}")
        assertEquals(AiProblem.KEY, client.ask(gemini, "s", "u").problem)
        answers(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-9 is not found for API version v1beta\",\"status\":\"NOT_FOUND\"}}")
        assertEquals(AiProblem.MODEL, client.ask(gemini, "s", "u").problem)
        answers(429, "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota\",\"status\":\"RESOURCE_EXHAUSTED\"}}")
        assertEquals(AiProblem.LIMIT, client.ask(gemini, "s", "u").problem)
        answers(503, "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}")
        answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.SERVER, answer.problem)
        assertEquals("HTTP 503 The model is overloaded.", answer.detail)
        answers(400, "{\"error\":{\"code\":400,\"message\":\"Invalid JSON payload received.\",\"status\":\"INVALID_ARGUMENT\"}}")
        assertEquals(AiProblem.SERVER, client.ask(gemini, "s", "u").problem)
        answers(502, "<html>Bad Gateway</html>")
        answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.SERVER, answer.problem)
        assertEquals("HTTP 502", answer.detail)
        answers(200, "not json")
        assertEquals(AiProblem.SERVER, client.ask(gemini, "s", "u").problem)

        server.offline = true
        answer = client.ask(gemini, "s", "u")
        assertEquals(AiProblem.NO_INTERNET, answer.problem)
        assertEquals("", answer.text)
    }

    @Test
    fun theModelsOfGemini() {
        answers(
            200,
            "{\"models\":[" +
                "{\"name\":\"models/gemini-3.6-flash\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]}," +
                "{\"name\":\"models/gemini-embedding-2\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
                "{\"name\":\"models/gemini-3.8-flash-tts\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
                "{\"name\":\"models/gemini-3.1-flash-image\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
                "{\"name\":\"models/gemini-4-hydrogen\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
                "{\"name\":\"models/gemini-4-carbon\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
                "{\"name\":\"models/gemini-3.8-live\",\"supportedGenerationMethods\":[\"bidiGenerateContent\"]}," +
                "{\"displayName\":\"no name\"}]}"
        )
        val models = client.models(gemini)
        assertNull(models.problem)
        assertEquals(
            "the one the app suggests first, then the newest names; nothing that makes speech, pictures or numbers",
            listOf("gemini-4-hydrogen", "gemini-4-carbon", "gemini-3.6-flash"),
            models.names
        )
        val request = server.last
        assertEquals("GET", request.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", request.url)
        assertEquals("KEY-123", request.headers["x-goog-api-key"])
        assertNull(request.body)

        answers(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\"}}")
        assertEquals(AiProblem.KEY, client.models(gemini).problem)
        server.offline = true
        assertEquals(AiProblem.NO_INTERNET, client.models(gemini).problem)
    }

    // ------------------------------------------------------------ the way of OpenAI

    @Test
    fun otherServicesAreAskedTheWayOfOpenAi() {
        answers(200, "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\" Your glucose was mostly in range. \"},\"finish_reason\":\"stop\"}]}")
        val answer = client.ask(openAi, "THE RULES", "THE QUESTION")
        assertEquals("Your glucose was mostly in range.", answer.text)

        val request = server.last
        assertEquals("POST", request.method)
        assertEquals("https://api.openai.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test-456", request.headers["Authorization"])
        val body = JSONObject(request.body!!)
        assertEquals("gpt-test", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("THE RULES", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("THE QUESTION", messages.getJSONObject(1).getString("content"))
        assertEquals("only what every such service understands", setOf("model", "messages"), body.keys().asSequence().toSet())

        // OpenAI is spoken to the way OpenAI describes it, at its own address
        val openAiToo = AiConfig(AiProvider.OPENAI, "https://api.openai.com/v1", "k", "gpt-5-mini")
        client.ask(openAiToo, "s", "u")
        assertEquals("https://api.openai.com/v1/chat/completions", server.last.url)
    }

    @Test
    fun anAnswerInPieces() {
        answers(200, "{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Part one. \"},{\"type\":\"image\",\"url\":\"x\"},{\"type\":\"text\",\"text\":\"Part two.\"}]}}]}")
        assertEquals("Part one. Part two.", client.ask(openAi, "s", "u").text)
    }

    @Test
    fun refusalsAndErrorsOfOtherServices() {
        answers(200, "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I cannot help with that.\"},\"finish_reason\":\"stop\"}]}")
        var answer = client.ask(openAi, "s", "u")
        assertEquals(AiProblem.BLOCKED, answer.problem)
        assertEquals("I cannot help with that.", answer.detail)
        answers(200, "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"content_filter\"}]}")
        assertEquals(AiProblem.BLOCKED, client.ask(openAi, "s", "u").problem)
        answers(200, "{\"choices\":[]}")
        assertEquals(AiProblem.EMPTY, client.ask(openAi, "s", "u").problem)
        answers(200, "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}")
        assertEquals(AiProblem.EMPTY, client.ask(openAi, "s", "u").problem)

        answers(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-te**456.\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}")
        answer = client.ask(openAi, "s", "u")
        assertEquals(AiProblem.KEY, answer.problem)
        answers(404, "{\"error\":{\"message\":\"The model `gpt-test` does not exist or you do not have access to it.\",\"code\":\"model_not_found\"}}")
        assertEquals(AiProblem.MODEL, client.ask(openAi, "s", "u").problem)
        answers(400, "{\"error\":{\"message\":\"The model `x` does not exist\"}}")
        assertEquals(AiProblem.MODEL, client.ask(openAi, "s", "u").problem)
        answers(429, "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\",\"type\":\"insufficient_quota\"}}")
        assertEquals(AiProblem.LIMIT, client.ask(openAi, "s", "u").problem)

        // Gemini spoken to the OpenAI way wraps its errors in a list
        answers(429, "[{\"error\":{\"code\":429,\"message\":\"Quota exceeded for metric\\nplease retry\",\"status\":\"RESOURCE_EXHAUSTED\"}}]")
        answer = client.ask(openAi, "s", "u")
        assertEquals(AiProblem.LIMIT, answer.problem)
        assertEquals("HTTP 429 Quota exceeded for metric please retry", answer.detail)
        // and some services just name the error
        answers(500, "{\"error\":\"internal\",\"message\":\"boom\"}")
        assertEquals("HTTP 500 internal", client.ask(openAi, "s", "u").detail)
        answers(500, "{\"detail\":\"upstream timeout\"}")
        assertEquals("HTTP 500 upstream timeout", client.ask(openAi, "s", "u").detail)
    }

    @Test
    fun theModelsOfAnotherService() {
        answers(
            200,
            "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5-mini\"},{\"id\":\"text-embedding-3-small\"},{\"id\":\"gpt-5\"},{\"id\":\"whisper-1\"}," +
                "{\"id\":\"dall-e-3\"},{\"id\":\"gpt-5-mini\"},{\"id\":\"omni-moderation-latest\"},{\"id\":\"tts-1\"}]}"
        )
        val models = client.models(openAi)
        assertEquals(listOf("gpt-5-mini", "gpt-5"), models.names)
        assertEquals("https://api.openai.com/v1/models", server.last.url)
        assertEquals("Bearer sk-test-456", server.last.headers["Authorization"])

        // a service with names the app has never heard of: all of them are shown
        answers(200, "{\"data\":[{\"id\":\"image-live-audio-thing\"}]}")
        assertEquals(listOf("image-live-audio-thing"), client.models(openAi).names)
        answers(200, "{\"data\":[]}")
        assertTrue(client.models(openAi).names.isEmpty())
        answers(401, "{\"error\":{\"message\":\"bad key\"}}")
        assertEquals(AiProblem.KEY, client.models(openAi).problem)
    }

    // ------------------------------------------------------------ the words for the AI

    @Test
    fun theRulesForTheAi() {
        val rules = AiPrompt.system(AnswerLanguage.SOMALI)
        assertTrue(rules.contains("Answer in Somali."))
        assertTrue(rules.contains("Never give a dose of insulin or of any medicine"))
        assertTrue(rules.contains("talk to a doctor or diabetes nurse soon"))
        assertTrue(rules.contains("this is not medical advice"))
        assertTrue(AiPrompt.system(AnswerLanguage.ARABIC).contains("Answer in Arabic."))
        assertTrue(AiPrompt.system(AnswerLanguage.ENGLISH).contains("Answer in English."))
    }

    @Test
    fun theOwnQuestionOfTheUserGoesAtTheEnd() {
        assertEquals("the numbers\n", AiPrompt.user("the numbers\n", ""))
        assertEquals("the numbers\n", AiPrompt.user("the numbers\n", "   \n "))
        assertEquals("the numbers\n\nMY OWN QUESTION\nWhy am I low at night?\n", AiPrompt.user("the numbers\n\n", "  Why am I\n low   at night? "))
        val long = AiPrompt.user("n", "x".repeat(2000))
        assertEquals("a very long question is cut", 500, long.substringAfter("MY OWN QUESTION\n").trim().length)
    }
}
