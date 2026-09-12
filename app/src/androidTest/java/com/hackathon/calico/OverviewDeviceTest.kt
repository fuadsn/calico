package com.hackathon.calico

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

class OverviewDeviceTest {
    @Test fun overviewShowsGaugeAndStats() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, MainActivity::class.java)
            .putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
                if (node == null) return false
                if (node.text?.toString()?.contains(text) == true) return true
                return (0 until node.childCount).any { contains(node.getChild(it), text) }
            }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!contains(i.uiAutomation.rootInActiveWindow, "Today Stats") && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            val root = i.uiAutomation.rootInActiveWindow
            for (label in listOf("Overview", "Activity", "Estimated daily calories", "Progress", "Today Stats")) {
                assertTrue("Missing $label", contains(root, label))
            }
            i.uiAutomation.takeScreenshot().let { bitmap ->
                File(i.targetContext.cacheDir, "full-overview.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally { i.runOnMainSync { activity.finish() } }
    }

    @Test fun dailyStatsAccumulateWithoutChangingOtherDays() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "overview-test-${System.nanoTime()}"
        val ctx = object : ContextWrapper(base) {
            override fun getSharedPreferences(n: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val progress = Progress(ctx)
            val today = LocalDate.now()
            progress.recordWorkout(today, reps = 12, secs = 60, kcal = 4)
            progress.recordWorkout(today, reps = 8, secs = 45, kcal = 3)
            assertEquals(Day(20, 105, 7), progress.day(today))
            assertEquals(Day(), progress.day(today.minusDays(1)))
            assertEquals(2, progress.completed)
            assertEquals(setOf(today), progress.dates)
        } finally { base.deleteSharedPreferences(name) }
    }
}
