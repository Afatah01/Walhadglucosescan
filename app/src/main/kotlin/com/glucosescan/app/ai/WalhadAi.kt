package com.glucosescan.app.ai

import com.glucosescan.app.BuildConfig
import com.glucosescan.app.core.AdvisorBlock
import com.glucosescan.app.core.AnswerLanguage
import com.glucosescan.app.net.HttpRequest
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** One earlier exchange, so that the AI can follow a question ("and at night?"). */
class AiTurn(
    /** What the person asked. */
    val question: String,
    /** What was answered. */
    val answer: String
)

/** What is asked of the AI. */
class AiQuestion(
    /** The numbers, as text (see [com.glucosescan.app.core.AiReport.build]). */
    val report: String,
    /** What the person asks in their own words, or "". */
    val question: String = "",
    val language: AnswerLanguage = AnswerLanguage.ENGLISH,
    /** The last few exchanges, oldest first. Only the newest are sent. */
    val history: List<AiTurn> = emptyList(),
    /**
     * What the phone itself makes of the same numbers. Used when the AI cannot be reached, so
     * that the person is never left with nothing.
     */
    val fallback: List<AdvisorBlock> = emptyList()
)

/**
 * The AI that is already built in.
 *
 * There is no key to type and no provider to choose, because there is nothing to choose: this
 * build knows one address, which is the small server described in `supabase/README.md`. The
 * provider key lives on that server and is never in this app, so it can be rotated or withdrawn
 * without shipping a new version.
 *
 * Everything here fails safe. If the address is missing, the internet is off, the server is busy,
 * or the daily limit is used up, the phone answers from its own rules and says so. It never
 * invents an AI answer and never shows its own words as if an AI had written them.
 */
class WalhadAi(
    private val transport: HttpTransport,
    /** The address of the AI door. Comes from secrets.properties at build time. */
    private val url: String = BuildConfig.WALHAD_AI_URL,
    /** The shared secret. Comes from secrets.properties at build time. */
    private val secret: String = BuildConfig.WALHAD_AI_SECRET,
    /** A random id for this install, so the server can count questions per phone. */
    private val install: String = ""
) {

    /** True when this build was given an address. False: the phone answers everything itself. */
    val isBuiltIn: Boolean
        get() = url.startsWith("https://") && secret.isNotBlank()

    /**
     * Asks the AI. Always returns something readable: on any problem the answer is the phone's
     * own reading of the numbers, with [AiAnswer.source] set to [AiSource.PHONE] and
     * [AiAnswer.problem] saying why the AI did not answer.
     */
    fun ask(ask: AiQuestion): AiAnswer {
        val local = localText(ask.fallback)

        if (!isBuiltIn) {
            return AiAnswer(local, null, "", AiSource.PHONE)
        }
        if (ask.report.isBlank()) {
            return AiAnswer(local, null, "", AiSource.PHONE)
        }

        val response = try {
            transport.send(
                HttpRequest("POST", url, headers(), body(ask), TIMEOUT_MILLIS)
            )
        } catch (e: IOException) {
            return AiAnswer(local, AiProblem.NO_INTERNET, Net.words(e), AiSource.PHONE)
        }

        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            return AiAnswer(local, AiProblem.SERVER, "the answer is not JSON", AiSource.PHONE)
        }

        if (json.optBoolean("ok", false)) {
            val answer = json.optString("answer", "").trim()
            // An empty answer is not an answer. Fall back rather than show a blank card.
            if (answer.isEmpty()) {
                return AiAnswer(local, AiProblem.EMPTY, "", AiSource.PHONE)
            }
            return AiAnswer(answer, null, "", AiSource.AI)
        }

        val problem = problemOf(json.optString("problem", ""))
        val detail = json.optString("detail", "").take(240)
        return AiAnswer(local, problem, detail, AiSource.PHONE)
    }

    // ------------------------------------------------------------------ the request

    private fun headers(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "x-walhad-secret" to secret
    )

    private fun body(ask: AiQuestion): String {
        val history = JSONArray()
        // Only the last few exchanges: the server also caps them.
        for (turn in ask.history.takeLast(MAX_HISTORY)) {
            history.put(JSONObject().put("q", turn.question).put("a", turn.answer))
        }
        return JSONObject()
            .put("report", ask.report)
            .put("question", ask.question.take(MAX_QUESTION))
            .put("language", ask.language.code)
            .put("history", history)
            .put("install", install)
            .toString()
    }

    private fun problemOf(name: String): AiProblem = when (name) {
        "limit" -> AiProblem.LIMIT
        "key" -> AiProblem.KEY
        "blocked" -> AiProblem.BLOCKED
        "empty" -> AiProblem.EMPTY
        "model" -> AiProblem.MODEL
        else -> AiProblem.SERVER
    }

    companion object {
        /** A long answer needs time, and a phone on a slow connection needs more. */
        const val TIMEOUT_MILLIS = 60_000

        /** The same caps the server applies, so a bad request is not sent at all. */
        const val MAX_QUESTION = 500
        const val MAX_HISTORY = 6

        /** The phone's own words, as one piece of text: each block a heading and its sentences. */
        internal fun localText(blocks: List<AdvisorBlock>): String {
            if (blocks.isEmpty()) {
                return ""
            }
            val out = StringBuilder()
            for (block in blocks) {
                if (out.isNotEmpty()) {
                    out.append('\n')
                }
                out.append(block.title).append('\n')
                for (line in block.lines) {
                    out.append(line.trim()).append('\n')
                }
            }
            return out.toString().trim()
        }
    }
}
