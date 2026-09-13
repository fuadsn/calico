package com.calico.roomscan

import android.view.View
import android.view.animation.PathInterpolator

/**
 * The app's motion for the View-based AR screens, matching Motion.fade() in :app
 * (220 ms, fast-out-slow-in), so native and Compose screens move the same way.
 */
internal object ViewMotion {
    private const val DURATION_MS = 220L
    private val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** Sheets rise into place when a screen opens. */
    fun riseIn(view: View) {
        view.post {
            view.translationY = view.height * 0.25f
            view.alpha = 0f
            view.animate().translationY(0f).alpha(1f).setDuration(DURATION_MS + 60).setInterpolator(EASE).start()
        }
    }

    /** Fades between VISIBLE and [hidden], instead of popping. */
    fun fade(view: View, show: Boolean, hidden: Int = View.GONE) {
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) { view.alpha = 0f; view.visibility = View.VISIBLE }
            view.animate().alpha(1f).setDuration(DURATION_MS).setInterpolator(EASE).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(DURATION_MS).setInterpolator(EASE)
                .withEndAction { view.visibility = hidden }.start()
        }
    }
}
