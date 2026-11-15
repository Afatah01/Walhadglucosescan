package com.glucosescan.app.ai

import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.data.AiProvider
import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpResponse
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** How to reach the AI service the user has set up. */
class AiConfig(
    val provider: AiProvider,
    /** The address of the service without a slash at its end, for example "https://api.openai.com/v1". */
    val url: String,
    val key: String,
    val model: String
)

enum class AiProblem {
    /** No answer: no internet, or the service cannot be reached. */
    NO_INTERNET,

    /** The service does not accept the key. */
    KEY,

    /** The service does not know the model (or the address is not the right one). */
    MODEL,

    /** Too many questions, or the free amount is used up. */
    LIMIT,

    /** The service refused to answer this text. */
    BLOCKED,

    /** The answer was empty. */
    EMPTY,

    /** The service answered with an error. */
    SERVER
}

/** Where the words came from. The screen always shows this, so nothing is ever passed off as more than it is. */
enum class AiSource {
    /** An AI service answered. */
    AI,

    /** The phone answered, from its own rules (see core/Advisor.kt). */
    PHONE
}

/** The answer of the AI, or why there is none. */
class AiAnswer(
    val text: String,
    val problem: AiProblem?,
    /** Short words of the service about the problem (English), or "". */
    val detail: String = "",
    /**
     * Who wrote [text]. When this is [AiSource.PHONE] and [problem] is set, the AI could not be
     * reached and the phone answered instead: the screen says both things, and never shows the
     * phone's words as if an AI had written them.
     */
    val source: AiSource = AiSource.AI
) {
    val isOk: Boolean
        get() = problem == null
}

/** The models the service offers, or why the list could not be fetched. */
class AiModels(
    val names: List<String>,
    val problem: AiProblem?,
    val detail: String = ""
)

/**
 * Asks an AI service a question over the internet. Two ways of asking are built in:
 * - Google Gemini, the way Google describes it ("generateContent");
 * - the way of OpenAI ("chat completions"), which many other services understand too.
 *
 * Only the model and the two texts are sent, nothing that only some services know. So the
 * same question works with old and new models.
 */
class AiClient(private val transport: HttpTransport) {

    /**
     * @param system how the AI shall behave (see [AiPrompt])
     * @param user the question with the numbers
     */
    fun ask(config: AiConfig, system: String, user: String): AiAnswer {
        val request = if (config.provider == AiProvider.GEMINI) {
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            HttpRequest("POST", config.url + "/models/" + geminiModel(config.model) + ":generateContent", geminiHeaders(config), body.toString(), ANSWER_TIMEOUT_MILLIS)
        } else {
            val body = JSONObject()
                .put("model", config.model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
            HttpRequest("POST", config.url + "/chat/completions", openAiHeaders(config), body.toString(), ANSWER_TIMEOUT_MILLIS)
        }
        val response = try {
            transport.send(request)
        } catch (e: IOException) {
            return AiAnswer("", AiProblem.NO_INTERNET, Net.words(e))
        }
        if (!response.isSuccess) {
            val failure = failure(response)
            return AiAnswer("", failure.first, failure.second)
        }
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            return AiAnswer("", AiProblem.SERVER, "the answer is not JSON")
        }
        return if (config.provider == AiProvider.GEMINI) geminiAnswer(json) else openAiAnswer(json)
    }

    /** The names of the models that can answer a text, the newest names first. */
    fun models(config: AiConfig): AiModels {
        val request = if (config.provider == AiProvider.GEMINI) {
            HttpRequest("GET", config.url + "/models?pageSize=1000", geminiHeaders(config))
        } else {
            HttpRequest("GET", config.url + "/models", openAiHeaders(config))
        }
        val response = try {
            transport.send(request)
        } catch (e: IOException) {
            return AiModels(emptyList(), AiProblem.NO_INTERNET, Net.words(e))
        }
        if (!response.isSuccess) {
            val failure = failure(response)
            return AiModels(emptyList(), failure.first, failure.second)
        }
        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            return AiModels(emptyList(), AiProblem.SERVER, "the answer is not JSON")
        }
        val all = ArrayList<String>()
        val list = json.optJSONArray("models") ?: json.optJSONArray("data") ?: JSONArray()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val name = geminiModel(text(entry, "name").ifEmpty { text(entry, "id") })
            if (name.isEmpty()) {
                continue
            }
            val methods = entry.optJSONArray("supportedGenerationMethods")
            if (methods != null && !contains(methods, "generateContent")) {
                continue // a Gemini model that cannot answer a text, for example one for search numbers
            }
            all.add(name)
        }
        // Models that make pictures, speech or numbers cannot explain glucose. If the filter
        // takes everything away, the whole list is shown: better too much than nothing.
        val forText = all.filter { name -> NOT_FOR_TEXT.none { name.lowercase(Locale.US).contains(it) } }
        val names = (if (forText.isEmpty()) all else forText).distinct().sortedDescending()
        val first = config.provider.defaultModel
        return AiModels(if (first in names) listOf(first) + names.filter { it != first } else names, null)
    }

    // ------------------------------------------------------------------ Google Gemini

    private fun geminiHeaders(config: AiConfig): Map<String, String> =
        mapOf("x-goog-api-key" to config.key, "Content-Type" to "application/json", "Accept" to "application/json")

    /** Google writes model names as "models/gemini-…" in lists and as "gemini-…" in addresses. */
    private fun geminiModel(name: String): String = name.trim().removePrefix("models/")

    private fun geminiAnswer(json: JSONObject): AiAnswer {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val reason = json.optJSONObject("promptFeedback")?.let { text(it, "blockReason") } ?: ""
            return if (reason.isNotEmpty()) AiAnswer("", AiProblem.BLOCKED, reason) else AiAnswer("", AiProblem.EMPTY, "no candidates")
        }
        val answer = StringBuilder()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) {
                    continue // what the model thought on the way, not its answer
                }
                answer.append(text(part, "text"))
            }
        }
        val words = answer.toString().trim()
        if (words.isNotEmpty()) {
            return AiAnswer(words, null)
        }
        val finish = text(candidate, "finishReason")
        return if (finish.isNotEmpty() && finish != "STOP" && finish != "MAX_TOKENS") AiAnswer("", AiProblem.BLOCKED, finish) else AiAnswer("", AiProblem.EMPTY, finish)
    }

    // ------------------------------------------------------------------ the way of OpenAI

    private fun openAiHeaders(config: AiConfig): Map<String, String> =
        mapOf("Authorization" to "Bearer " + config.key, "Content-Type" to "application/json", "Accept" to "application/json")

    private fun openAiAnswer(json: JSONObject): AiAnswer {
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return AiAnswer("", AiProblem.EMPTY, "no choices")
        val message = choice.optJSONObject("message")
        val answer = StringBuilder()
        val content = message?.opt("content")
        if (content is String) {
            answer.append(content)
        } else if (content is JSONArray) {
            // some services give the answer in pieces
            for (i in 0 until content.length()) {
                val piece = content.optJSONObject(i) ?: continue
                if (text(piece, "type").let { it.isEmpty() || it == "text" || it == "output_text" }) {
                    answer.append(text(piece, "text"))
                }
            }
        }
        val words = answer.toString().trim()
        if (words.isNotEmpty()) {
            return AiAnswer(words, null)
        }
        val refusal = if (message != null) text(message, "refusal") else ""
        val finish = text(choice, "finish_reason")
        return if (refusal.isNotEmpty() || finish == "content_filter") AiAnswer("", AiProblem.BLOCKED, refusal.ifEmpty { finish }.take(200)) else AiAnswer("", AiProblem.EMPTY, finish)
    }

    // ------------------------------------------------------------------ problems

    private fun failure(response: HttpResponse): Pair<AiProblem, String> {
        val message = errorMessage(response.body)
        val detail = ("HTTP " + response.code + " " + message).trim().take(240)
        val lower = message.lowercase(Locale.US)
        val problem = when {
            response.code == 401 || response.code == 403 -> AiProblem.KEY
            response.code == 429 -> AiProblem.LIMIT
            response.code == 404 -> AiProblem.MODEL
            response.code == 400 && (lower.contains("api key") || lower.contains("api_key")) -> AiProblem.KEY
            response.code == 400 && lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("not supported")) -> AiProblem.MODEL
            else -> AiProblem.SERVER
        }
        return Pair(problem, detail)
    }

    /** What the service says about its error. The services wrap it in different ways. */
    private fun errorMessage(body: String): String {
        val trimmed = body.trim()
        val json: JSONObject? = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0) else JSONObject(trimmed)
        } catch (e: JSONException) {
            null
        }
        if (json == null) {
            return ""
        }
        val error = json.opt("error")
        val found = when {
            error is JSONObject -> text(error, "message").ifEmpty { text(error, "status") }
            error is String -> error
            else -> text(json, "message").ifEmpty { text(json, "detail") }
        }
        return found.replace('\n', ' ').trim()
    }

    private fun contains(list: JSONArray, word: String): Boolean {
        for (i in 0 until list.length()) {
            if (list.optString(i) == word) {
                return true
            }
        }
        return false
    }

    private fun text(json: JSONObject, name: String): String = if (json.isNull(name)) "" else (json.opt(name) as? String ?: "")

    companion object {
        /** A model that thinks before it answers can need a long time. */
        const val ANSWER_TIMEOUT_MILLIS = 150_000

        private val NOT_FOR_TEXT = listOf(
            "embed", "tts", "image", "imagen", "banana", "veo", "omni", "live", "transcribe", "whisper", "audio", "dall-e",
            "sora", "moderation", "rerank", "guard", "realtime", "aqa", "robotics", "computer-use", "speech", "vision-preview"
        )
    }
}

/** The words that tell the AI how to answer. */
object AiPrompt {
    /**
     * The rules for the AI. They ask for plain words and forbid doses; an AI can still
     * ignore them, and the screen says so.
     */
    fun system(language: AnswerLanguage): String {
        return "You explain the data of a glucose sensor to the person who wears it. This person is not a doctor.\n" +
            "Rules:\n" +
            "- Answer in " + language.english + ".\n" +
            "- Use short, simple sentences and everyday words.\n" +
            "- Never give a dose of insulin or of any medicine, and never tell the person to change a dose or a medicine. That is for the doctor.\n" +
            "- Do not name a diagnosis.\n" +
            "- If the data show very low values (under 54 mg/dL or 3.0 mmol/L) or many low values, say clearly that the person should talk to a doctor or diabetes nurse soon.\n" +
            "- Only use the numbers you are given. If they are not enough to say something, say that.\n" +
            "- Plain text only. No tables. Do not use the signs * or #. Start list lines with \"- \".\n" +
            "- At most about 350 words.\n" +
            "- End with one sentence that says: this is not medical advice."
    }

    /**
     * The question with the numbers.
     * @param report the text of AiReport
     * @param question what the user asks in own words, or ""
     */
    fun user(report: String, question: String): String {
        val own = question.trim().replace(Regex("\\s+"), " ").take(MAX_QUESTION)
        return if (own.isEmpty()) report else report.trimEnd() + "\n\nMY OWN QUESTION\n" + own + "\n"
    }

    const val MAX_QUESTION = 500
}
