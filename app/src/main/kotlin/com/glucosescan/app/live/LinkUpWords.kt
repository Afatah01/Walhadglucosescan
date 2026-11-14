package com.glucosescan.app.live

import android.content.Context
import com.glucosescan.app.R
import com.glucosescan.app.data.LinkUpState

/** The words for what the link to LibreLinkUp is doing. */
object LinkUpWords {
    fun state(context: Context, state: LinkUpState): String {
        return context.getString(
            when (state) {
                LinkUpState.OFF -> R.string.linkup_state_off
                LinkUpState.STARTING -> R.string.linkup_state_starting
                LinkUpState.RECEIVING -> R.string.linkup_state_receiving
                LinkUpState.NO_NEW_VALUE -> R.string.linkup_state_no_new
                LinkUpState.NO_INTERNET -> R.string.linkup_state_no_internet
                LinkUpState.WRONG_LOGIN -> R.string.linkup_state_wrong_login
                LinkUpState.NEEDS_STEP -> R.string.linkup_state_needs_step
                LinkUpState.LOCKED -> R.string.linkup_state_locked
                LinkUpState.NO_PATIENT -> R.string.linkup_state_no_patient
                LinkUpState.SERVER_PROBLEM -> R.string.linkup_state_server
                LinkUpState.CLOCK_WRONG -> R.string.linkup_state_clock
            }
        )
    }
}
