package com.glucosescan.app.ai

import android.content.Context

/** The words for what went wrong with the AI service. */
object AiWords {
    fun problem(context: Context, problem: AiProblem, detail: String): String {
        val words = context.getString(
            when (problem) {
                AiProblem.NO_INTERNET -> com.glucosescan.app.R.string.ai_error_no_internet
                AiProblem.KEY -> com.glucosescan.app.R.string.ai_error_key
                AiProblem.MODEL -> com.glucosescan.app.R.string.ai_error_model
                AiProblem.LIMIT -> com.glucosescan.app.R.string.ai_error_limit
                AiProblem.BLOCKED -> com.glucosescan.app.R.string.ai_error_blocked
                AiProblem.EMPTY -> com.glucosescan.app.R.string.ai_error_empty
                AiProblem.SERVER -> com.glucosescan.app.R.string.ai_error_server
            }
        )
        // what the service itself said helps the person who looks after the app
        val showDetail = detail.isNotEmpty() && problem != AiProblem.NO_INTERNET && problem != AiProblem.EMPTY
        return if (showDetail) context.getString(com.glucosescan.app.R.string.ai_error_detail, words, detail) else words
    }
}
