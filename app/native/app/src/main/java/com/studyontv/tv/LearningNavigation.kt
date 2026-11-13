package com.studyontv.tv

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent

/** Only immutable, already-computed state is read on the accessibility key path. */
data class LearningExitSnapshot(val canExit: Boolean, val publishedAt: Long)

object LearningNavigation {
    private const val REFRESH_MS = 1000L
    private const val ALLOW_LEASE_MS = 2000L
    @Volatile private var engine: LearningEngine? = null
    private var worker: Handler? = null
    private val refresh = object : Runnable {
        override fun run() {
            try { engine?.status() } catch (e: Exception) {
                android.util.Log.w("StudyOnTV", "Navigation state refresh failed", e)
            } finally { worker?.postDelayed(this, REFRESH_MS) }
        }
    }

    /** Called once the runtime owns this engine; isolated test engines never publish globally. */
    fun start(value: LearningEngine) {
        engine = value
        if (worker == null) {
            val thread = HandlerThread("LearningNavigation").apply { start() }
            worker = Handler(thread.looper)
        }
        worker?.removeCallbacks(refresh)
        worker?.post(refresh)
    }

    val snapshot: LearningExitSnapshot? get() = engine?.exitSnapshot
    fun policyAllowsExit(now: Long = SystemClock.elapsedRealtime()): Boolean = allows(snapshot, now)

    internal fun allows(current: LearningExitSnapshot?, now: Long): Boolean {
        current ?: return false
        // A stalled refresher must not leave an expired temporary unlock open indefinitely.
        return current.canExit && now - current.publishedAt in 0..ALLOW_LEASE_MS
    }

    fun shouldBlockHome(): Boolean {
        val activity = Foreground.resumedActivity()
        val learningSurface = when (activity) {
            is MainActivity -> true
            is ParentActivity -> activity.requiresParentVerification
            null -> GateService.presentedOverlay() || WatchAccessibilityService.presentedGate()
            else -> AnswerInput.owns(activity)
        }
        return learningSurface && !policyAllowsExit()
    }
}

/** Keep the same decision for DOWN, repeats and UP, even if the policy changes mid-press. */
class HomeKeyFilter(private val blocked: () -> Boolean) {
    private data class Press(val downTime: Long, val consume: Boolean)
    private val presses = mutableMapOf<Int, Press>()

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val previous = presses[event.deviceId]?.takeIf { it.downTime == event.downTime }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val press = previous ?: Press(event.downTime, event.repeatCount == 0 && blocked())
                presses[event.deviceId] = press
                press.consume
            }
            KeyEvent.ACTION_UP -> {
                if (previous != null) presses.remove(event.deviceId)
                previous?.consume == true
            }
            else -> false
        }
    }

    fun reset() = presses.clear()
}
