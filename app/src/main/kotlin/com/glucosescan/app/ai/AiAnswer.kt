package com.glucosescan.app.ai

/**
 * What came back from the AI door, and where the words came from.
 *
 * The door is the small server described in `supabase/README.md`. The provider
 * and its key are chosen there and nowhere else: nothing on the phone knows or
 * holds either.
 */
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
