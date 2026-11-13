package com.studyontv.tv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicitly destructive test setup, restricted to a disposable debug emulator. */
object HomeNavigationUiRegression {
    fun run(instrumentation: Instrumentation): String {
        check(BuildConfig.DEBUG && Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val activities = mutableListOf<Activity>()
        val monitor = Instrumentation.ActivityMonitor(null as IntentFilter?, null, false)
        var web: WebView? = null
        var inputId = ""
        val passphrase = UUID.randomUUID().toString()
        var callbackMs = 0L
        instrumentation.addMonitor(monitor)
        try {
            onMain(instrumentation) {
                DeviceRuntime.ensure(context)
                AdminAuth.setPassword(context, passphrase)
                Prefs.setOnboarded(context, true)
                DeviceRuntime.learning.saveConfig(JSONObject().put("allDay", true).put("dailyFreeMinutes", 0)
                    .put("bootFreeMinutes", 0).put("forceFullscreenGate", true))
                DeviceRuntime.learning.remote("lock", 0, "test")
            }
            val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activities.add(main)
            waitUntil(instrumentation) { Foreground.resumedActivity() is MainActivity }
            onMain(instrumentation) { check(LearningNavigation.shouldBlockHome()) }

            // Invoke the actual callback while another thread owns both engine monitor and SQLite transaction.
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread {
                synchronized(DeviceRuntime.learning) {
                    val db = DeviceRuntime.store.writableDatabase
                    db.beginTransaction()
                    try { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    finally { db.endTransaction() }
                }
            }
            try {
                onMain(instrumentation) {
                    // Queue latency from unrelated main-thread work is a separate remaining risk;
                    // this runnable specifically proves the callback itself does not take either lock.
                    holder.start()
                    try {
                        check(held.await(2, TimeUnit.SECONDS))
                        val service = WatchAccessibilityService()
                        val time = SystemClock.uptimeMillis()
                        val start = SystemClock.elapsedRealtime()
                        check(service.onKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0)))
                        check(service.onKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0)))
                        callbackMs = SystemClock.elapsedRealtime() - start
                        check(callbackMs < 100) { "Home callback waited for engine/database lock: ${callbackMs}ms" }
                    } finally { release.countDown() }
                }
            } finally { release.countDown(); holder.join(3000) }

            onMain(instrumentation) {
                web = WebView(context)
                InputSupport.openText(main, web!!, "草稿", "Home 范围验证")
            }
            val input = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                ?.takeUnless { it === main } ?: waitForInput(instrumentation)
            activities.add(input)
            waitUntil(instrumentation) { AnswerInput.owns(Foreground.resumedActivity() ?: return@waitUntil false) }
            onMain(instrumentation) {
                inputId = input.intent.getStringExtra("requestId") ?: error("Missing input request")
                check(LearningNavigation.shouldBlockHome())
            }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitUntil(instrumentation) { !Foreground.hasResumedActivity() }
            onMain(instrumentation) {
                check(AnswerInput.active)
                check(!LearningNavigation.shouldBlockHome())
            }
            val parent = instrumentation.startActivitySync(Intent(context, ParentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ParentActivity
            activities.add(parent)
            onMain(instrumentation) {
                check(parent.requiresParentVerification && LearningNavigation.shouldBlockHome())
                descendants(parent.window.decorView).filterIsInstance<EditText>().first().setText(passphrase)
                descendants(parent.window.decorView).filterIsInstance<Button>()
                    .first { it.text.toString() == "验证并打开连接页" }.performClick()
            }
            waitUntil(instrumentation) { !parent.requiresParentVerification }
            onMain(instrumentation) { check(!LearningNavigation.shouldBlockHome()) }

            val stopped = ParentActivity::class.java.getDeclaredField("stoppedAt").apply { isAccessible = true }
            var previousStop = 0L
            var expiredStop = 0L
            onMain(instrumentation) { previousStop = stopped.getLong(parent) }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // onPause clears Foreground before onStop records stoppedAt. Wait for that write,
            // otherwise the real onStop can overwrite the simulated elapsed minute below.
            waitUntil(instrumentation) {
                !Foreground.hasResumedActivity() && stopped.getLong(parent) > previousStop
            }
            onMain(instrumentation) {
                expiredStop = (SystemClock.elapsedRealtime() - 61000).also { check(it > 0) }
                stopped.setLong(parent, expiredStop)
                check(stopped.getLong(parent) == expiredStop)
                parent.startActivity(Intent(parent, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitUntil(instrumentation) { Foreground.resumedActivity() === parent }
            onMain(instrumentation) {
                check(stopped.getLong(parent) == expiredStop) { "Parent stop timestamp was overwritten after simulation" }
                check(parent.requiresParentVerification && LearningNavigation.shouldBlockHome())
            }
            onMain(instrumentation) {
                input.startActivity(Intent(input, input.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            waitUntil(instrumentation) { Foreground.resumedActivity() === input }
            onMain(instrumentation) {
                check(AnswerInput.owns(input) && LearningNavigation.shouldBlockHome())
                DeviceRuntime.learning.remote("unlock", 1, "test")
                check(!LearningNavigation.shouldBlockHome()) { "Unlock must publish before the API returns" }
            }
            return "PASS: actual callback under engine/SQLite lock (${callbackMs}ms); input foreground/background; parent verification and timeout; immediate unlock\n"
        } finally {
            instrumentation.removeMonitor(monitor)
            onMain(instrumentation) {
                if (inputId.isNotEmpty()) AnswerInput.complete(inputId, null)
                Prefs.setOnboarded(context, false)
                activities.asReversed().forEach { it.finish() }
                web?.destroy()
                context.stopService(Intent(context, GateService::class.java))
            }
        }
    }

    private fun waitForInput(instrumentation: Instrumentation): Activity {
        waitUntil(instrumentation) { Foreground.resumedActivity()?.let { AnswerInput.owns(it) } == true }
        return Foreground.resumedActivity() ?: error("Input disappeared")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /** runOnMainSync does not marshal assertion failures; keep them on the runner thread. */
    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (caught: Throwable) { failure = caught }
        }
        failure?.let { throw it }
    }

    private fun waitUntil(instrumentation: Instrumentation, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            onMain(instrumentation) { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        error("Navigation lifecycle timed out")
    }
}
