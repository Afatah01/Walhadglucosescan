package com.glucosescan.app.linkup

import com.glucosescan.app.data.GlucoseStore
import com.glucosescan.app.data.LinkUpHandler
import com.glucosescan.app.data.LinkUpState
import com.glucosescan.app.data.LiveLog
import com.glucosescan.app.data.Settings
import com.glucosescan.app.net.HttpTransport
import com.glucosescan.app.net.Net

/** What one look at LibreLinkUp brought. */
class LinkUpLook(
    val state: LinkUpState,
    /** True if a value was saved that is newer than everything before. */
    val newValue: Boolean,
    /** The exact time of the newest value LibreLinkUp has (milliseconds since 1970), or 0. */
    val newestTime: Long = 0L
)

/** What logging in brought: the people to choose from, or why it did not work. */
class LinkUpConnect(
    val patients: List<LinkUpPatient>,
    val failure: LinkUpFailure?
) {
    val isOk: Boolean
        get() = failure == null
}

/**
 * Does the talking with LibreLinkUp: logs in, keeps the login alive, fetches the values and
 * saves them. Everything in here waits for the internet, so it must run away from the screen
 * (see [Net.background]).
 *
 * It is careful with the password. A server that refuses the login is not asked again until
 * the user has typed the password anew: three wrong tries lock a LibreLinkUp account.
 */
class LinkUpRunner(
    private val store: GlucoseStore,
    private val settings: Settings,
    private val transport: HttpTransport = Net.transport
) {
    private fun api(): LinkUpApi = LinkUpApi(transport, settings.linkUpVersion.ifEmpty { LinkUpApi.DEFAULT_VERSION })

    private fun keepVersion(api: LinkUpApi) {
        if (api.version != LinkUpApi.DEFAULT_VERSION || settings.linkUpVersion.isNotEmpty()) {
            if (api.version != settings.linkUpVersion) {
                settings.linkUpVersion = api.version
            }
        }
    }

    // ------------------------------------------------------------------ setting it up

    /**
     * Logs in with what the user typed and asks whose values the account may see. Nothing is
     * switched on yet: that is [choose].
     */
    fun connect(email: String, password: String, now: Long): LinkUpConnect {
        val api = api()
        val reply = api.login(email, password, settings.linkUpRegion)
        keepVersion(api)
        val login = reply.value
        if (login == null) {
            val failure = reply.failure!!
            log(now, "login: ${failure.problem.name}, ${failure.detail}")
            if (failure.problem == LinkUpProblem.NEEDS_STEP) {
                // remember what was typed: after the step the login goes on without typing again
                settings.linkUpEmail = email
                settings.linkUpPassword = password
                rememberStep(failure)
            }
            if (failure.problem == LinkUpProblem.LOCKED) {
                settings.linkUpWaitUntil = now + failure.waitSeconds * 1000L
            }
            return LinkUpConnect(emptyList(), failure)
        }
        settings.linkUpEmail = email
        settings.linkUpPassword = password
        return afterLogin(api, login, now)
    }

    /** The user has accepted the step Abbott asked for. Goes on with the login. */
    fun acceptStep(now: Long): LinkUpConnect {
        val step = settings.linkUpStep
        val token = settings.linkUpStepToken
        if (step.isEmpty() || token.isEmpty()) {
            return LinkUpConnect(emptyList(), LinkUpFailure(LinkUpProblem.BAD_ANSWER, "no step is waiting"))
        }
        val api = api()
        val reply = api.acceptStep(step, token, settings.linkUpRegion)
        keepVersion(api)
        val login = reply.value
        if (login == null) {
            val failure = reply.failure!!
            log(now, "step $step: ${failure.problem.name}, ${failure.detail}")
            if (failure.problem == LinkUpProblem.NEEDS_STEP) {
                rememberStep(failure) // the next step, for example "pp" after "tou"
            } else if (failure.problem == LinkUpProblem.BAD_ANSWER || failure.problem == LinkUpProblem.LOGIN_EXPIRED || failure.problem == LinkUpProblem.SERVER) {
                // The pass for the step is too old (it is good for an hour), or the step is done
                // but the answer was no login. Either way the old step is of no use any more:
                // log in the plain way. If the server still wants a yes, it asks again with a new pass.
                settings.linkUpStep = ""
                settings.linkUpStepToken = ""
                if (settings.linkUpPassword.isNotEmpty()) {
                    return connect(settings.linkUpEmail, settings.linkUpPassword, now)
                }
            }
            return LinkUpConnect(emptyList(), failure)
        }
        settings.linkUpStep = ""
        settings.linkUpStepToken = ""
        return afterLogin(api, login, now)
    }

    private fun afterLogin(api: LinkUpApi, login: LinkUpLogin, now: Long): LinkUpConnect {
        keepLogin(login)
        settings.linkUpStep = ""
        settings.linkUpStepToken = ""
        settings.linkUpWaitUntil = 0L
        val list = api.patients(login)
        keepVersion(api)
        val patients = list.value
        if (patients == null) {
            log(now, "connections: ${list.failure!!.problem.name}, ${list.failure.detail}")
            return LinkUpConnect(emptyList(), list.failure)
        }
        log(now, "logged in, region \"${login.region}\", ${patients.size} connection(s)")
        return LinkUpConnect(patients, null)
    }

    /** Switches the link on for one person. The service then fetches the values. */
    fun choose(patient: LinkUpPatient) {
        settings.linkUpPatientId = patient.id
        settings.linkUpPatientName = patient.name
        settings.linkUpFailures = 0
        settings.linkUpState = LinkUpState.STARTING
        settings.linkUpEnabled = true
    }

    /** Switches the link off. The values that were fetched stay on the phone. */
    fun switchOff(forgetLogin: Boolean) {
        settings.linkUpEnabled = false
        settings.linkUpState = LinkUpState.OFF
        if (forgetLogin) {
            settings.forgetLinkUpLogin()
        }
    }

    // ------------------------------------------------------------------ once a minute

    /**
     * Asks LibreLinkUp for the newest values and saves them.
     * @param now milliseconds since 1970
     */
    fun look(now: Long): LinkUpLook {
        if (!settings.linkUpEnabled) {
            return LinkUpLook(LinkUpState.OFF, false)
        }
        val before = settings.linkUpState
        if (before.stopsAsking) {
            return LinkUpLook(before, false) // only the user can help: do not knock at the server again and again
        }
        if (now < settings.linkUpWaitUntil) {
            return LinkUpLook(before, false) // the server asked for a pause, or the last tries failed
        }
        settings.linkUpLastAskTime = now

        val api = api()
        var login = savedLogin(now) ?: logInAgain(api, now) ?: return failed(now)
        var patientId = settings.linkUpPatientId
        if (patientId.isEmpty()) {
            val list = api.patients(login)
            val first = list.value?.firstOrNull()
            if (first == null) {
                return failed(now, if (list.isOk) LinkUpFailure(LinkUpProblem.SERVER, "nobody shares with this account") else list.failure!!, noPatient = list.isOk)
            }
            patientId = first.id
            settings.linkUpPatientId = first.id
            settings.linkUpPatientName = first.name
        }

        var reply = api.values(login, patientId)
        if (reply.failure?.problem == LinkUpProblem.LOGIN_EXPIRED) {
            forgetPass()
            login = logInAgain(api, now) ?: return failed(now)
            reply = api.values(login, patientId)
        }
        keepVersion(api)
        val data = reply.value
        if (data == null) {
            val failure = reply.failure!!
            if (failure.problem == LinkUpProblem.SERVER || failure.problem == LinkUpProblem.BAD_ANSWER) {
                // maybe the person has stopped sharing: then the list does not hold them any more
                val list = api.patients(login)
                if (list.isOk && list.value!!.none { it.id == patientId }) {
                    return failed(now, failure, noPatient = true)
                }
            }
            return failed(now, failure)
        }

        val outcome = LinkUpHandler(store, settings).handle(data, now)
        settings.linkUpLastAnswerTime = now
        settings.linkUpFailures = 0
        settings.linkUpWaitUntil = 0L
        val state = when {
            outcome.clockWrong -> LinkUpState.CLOCK_WRONG
            outcome.newestTime != 0L && now - outcome.newestTime <= FRESH_MILLIS -> LinkUpState.RECEIVING
            else -> LinkUpState.NO_NEW_VALUE
        }
        if (state != before) {
            log(now, "state: ${state.name}")
        }
        settings.linkUpState = state
        return LinkUpLook(state, outcome.newValue, outcome.newestTime)
    }

    /** The saved login if it is still good, or null. */
    private fun savedLogin(now: Long): LinkUpLogin? {
        val token = settings.linkUpToken
        val userId = settings.linkUpUserId
        if (token.isEmpty() || userId.isEmpty()) {
            return null
        }
        val expires = settings.linkUpTokenExpires
        if (expires != 0L && now >= expires - EXPIRY_MARGIN_MILLIS) {
            return null
        }
        return LinkUpLogin(token, expires, userId, settings.linkUpRegion)
    }

    private var lastFailure: LinkUpFailure? = null

    /** Logs in with the saved password. Null if that does not work; [lastFailure] then says why. */
    private fun logInAgain(api: LinkUpApi, now: Long): LinkUpLogin? {
        val email = settings.linkUpEmail
        val password = settings.linkUpPassword
        if (email.isEmpty() || password.isEmpty()) {
            lastFailure = LinkUpFailure(LinkUpProblem.WRONG_LOGIN, "no password is saved")
            return null
        }
        val reply = api.login(email, password, settings.linkUpRegion)
        keepVersion(api)
        val login = reply.value
        if (login == null) {
            lastFailure = reply.failure
            if (reply.failure!!.problem == LinkUpProblem.NEEDS_STEP) {
                rememberStep(reply.failure)
            }
            return null
        }
        log(now, "logged in again")
        keepLogin(login)
        return login
    }

    private fun failed(now: Long, failure: LinkUpFailure? = lastFailure, noPatient: Boolean = false): LinkUpLook {
        val problem = failure?.problem ?: LinkUpProblem.SERVER
        val state = when {
            noPatient -> LinkUpState.NO_PATIENT
            problem == LinkUpProblem.NO_INTERNET -> LinkUpState.NO_INTERNET
            problem == LinkUpProblem.WRONG_LOGIN -> LinkUpState.WRONG_LOGIN
            problem == LinkUpProblem.NEEDS_STEP -> LinkUpState.NEEDS_STEP
            problem == LinkUpProblem.LOCKED -> LinkUpState.LOCKED
            problem == LinkUpProblem.LOGIN_EXPIRED -> LinkUpState.SERVER_PROBLEM
            else -> LinkUpState.SERVER_PROBLEM
        }
        val failures = settings.linkUpFailures + 1
        settings.linkUpFailures = failures
        when (state) {
            LinkUpState.LOCKED -> settings.linkUpWaitUntil = now + (failure?.waitSeconds ?: LinkUpApi.DEFAULT_LOCKOUT_SECONDS) * 1000L
            // A server that answers with errors is asked less and less often, up to every 10 minutes.
            LinkUpState.SERVER_PROBLEM -> settings.linkUpWaitUntil = now + Math.min(failures, MAX_PAUSE_MINUTES) * MINUTE - 5_000L
            LinkUpState.WRONG_LOGIN -> forgetPass()
            else -> {}
        }
        if (state != settings.linkUpState || failures <= 3) {
            log(now, "${state.name}: ${failure?.detail ?: ""}")
        }
        settings.linkUpState = state
        return LinkUpLook(state, false)
    }

    private fun keepLogin(login: LinkUpLogin) {
        settings.linkUpToken = login.token
        settings.linkUpTokenExpires = login.expires
        settings.linkUpUserId = login.userId
        settings.linkUpRegion = login.region
    }

    private fun forgetPass() {
        settings.linkUpToken = ""
        settings.linkUpTokenExpires = 0L
    }

    private fun rememberStep(failure: LinkUpFailure) {
        settings.linkUpStep = failure.step
        settings.linkUpStepToken = failure.stepToken
        if (failure.region.isNotEmpty()) {
            settings.linkUpRegion = failure.region
        }
    }

    private fun log(now: Long, text: String) {
        LiveLog.add(settings, now, "LibreLinkUp $text")
    }

    companion object {
        private const val MINUTE = 60_000L

        /** A value that is at most this old counts as fresh. */
        const val FRESH_MILLIS = 5 * MINUTE

        /** The login is renewed this long before it ends. */
        const val EXPIRY_MARGIN_MILLIS = 2 * MINUTE
        const val MAX_PAUSE_MINUTES = 10
    }
}
