package com.studyontv.tv

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

/** Isolated database; no household configuration, questions or passwords are changed. */
object HomeNavigationRegression {
    fun run(context: Context) {
        val service = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .installedAccessibilityServiceList.first { it.resolveInfo.serviceInfo.packageName == context.packageName }
        check(service.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
        check(service.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0)
        check(!LearningNavigation.allows(null, 1))
        check(!LearningNavigation.allows(LearningExitSnapshot(false, 100), 101))
        check(LearningNavigation.allows(LearningExitSnapshot(true, 100), 101))
        check(!LearningNavigation.allows(LearningExitSnapshot(true, 100), 2101))

        var blocked = true
        var decisions = 0
        val keys = HomeKeyFilter { decisions++; blocked }
        fun key(action: Int, time: Long, repeat: Int = 0, device: Int = 1, code: Int = KeyEvent.KEYCODE_HOME) =
            KeyEvent(time, time + repeat + 1, action, code, repeat, 0, device, 0)
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 1)))
        blocked = false
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 1, 1)))
        check(keys.handle(key(KeyEvent.ACTION_UP, 1)))
        check(decisions == 1)
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 2)))
        blocked = true
        check(!keys.handle(key(KeyEvent.ACTION_UP, 2)))
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 3)))
        blocked = false
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 4, device = 2)))
        check(keys.handle(key(KeyEvent.ACTION_UP, 3)))
        check(!keys.handle(key(KeyEvent.ACTION_UP, 4, device = 2)))
        blocked = true
        check(!keys.handle(key(KeyEvent.ACTION_UP, 5)))
        check(!keys.handle(key(KeyEvent.ACTION_DOWN, 6, 1)))
        check(!keys.handle(key(KeyEvent.ACTION_UP, 6)))
        val before = decisions
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER)) {
            check(!keys.handle(key(KeyEvent.ACTION_DOWN, 7, code = code)))
            check(!keys.handle(key(KeyEvent.ACTION_UP, 7, code = code)))
        }
        check(decisions == before)
        check(keys.handle(key(KeyEvent.ACTION_DOWN, 8)))
        keys.reset()
        check(!keys.handle(key(KeyEvent.ACTION_UP, 8)))

        val database = "home-navigation-regression.db"
        context.deleteDatabase(database)
        val store = LocalStore(context, database)
        try {
            val engine = LearningEngine(store)
            engine.saveConfig(JSONObject().put("allDay", true).put("dailyFreeMinutes", 0)
                .put("bootFreeMinutes", 0).put("questionsPerRound", 1).put("extraEnabled", false)
                .put("wrongReviewSeconds", 0).put("roundsLimitEnabled", false))
            check(!engine.exitSnapshot.canExit)
            engine.remote("unlock", 1, "test")
            check(engine.exitSnapshot.canExit)
            engine.remote("cancel", 0, "test")
            check(!engine.exitSnapshot.canExit)
            engine.remote("credit", 1, "test")
            check(engine.exitSnapshot.canExit)
            engine.saveConfig(JSONObject().put("allDay", false).put("weekSlots", JSONArray(List(7) { "0".repeat(48) })))
            check(!engine.exitSnapshot.canExit)
            engine.saveConfig(JSONObject().put("allDay", true))
            check(engine.exitSnapshot.canExit)
            val originalChild = store.activeChild()
            val second = store.createChild("测试孩子").getString("id")
            engine.switchChild(second)
            check(!engine.exitSnapshot.canExit)
            engine.switchChild(originalChild)
            check(engine.exitSnapshot.canExit)
            repeat(12) { engine.consume(5000) }
            check(!engine.exitSnapshot.canExit)
            store.upsertQuestion(JSONObject().put("id", "home-q").put("q", "1+1=?")
                .put("a", JSONArray(listOf("2", "3"))).put("k", 0))
            val round = engine.start()
            val answer = engine.answer(JSONObject().put("roundId", round.getString("id"))
                .put("nonce", round.getString("nonce")).put("selections", JSONArray().put(0)))
            check(answer.getBoolean("finished"))
            check(answer.getJSONObject("status").getBoolean("canExit") && engine.exitSnapshot.canExit)
            engine.remote("unlock", 1, "test")
            val override = JSONObject(store.get("remote_override"))
                .put("until", System.currentTimeMillis() + 1000)
                .put("untilElapsed", SystemClock.elapsedRealtime() + 1000)
            store.set("remote_override", override.toString())
            engine.remote("credit", -480, "test")
            check(engine.exitSnapshot.canExit)
            Thread.sleep(1100)
            check(!engine.status().getBoolean("canExit") && !engine.exitSnapshot.canExit)
        } finally {
            store.close()
            context.deleteDatabase(database)
        }
    }
}
