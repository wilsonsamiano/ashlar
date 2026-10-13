package com.wilson.win10launcher

import android.app.Activity
import android.os.Build

/** Book-page (WP7 turnstile) transitions between Start, All apps, and Settings. */
object PageTurn {
    fun forward(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.turn_open_enter,
                R.anim.turn_open_exit
            )
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.turn_open_enter, R.anim.turn_open_exit)
    }

    /** Register both directions so the system back gesture uses the page turn. */
    fun arm(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                R.anim.turn_open_enter,
                R.anim.turn_open_exit
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE,
                R.anim.turn_close_enter,
                R.anim.turn_close_exit
            )
        }
    }

    fun back(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.turn_close_enter, R.anim.turn_close_exit)
    }

    /** Theme switches should not play a page turn. */
    fun plainClose(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }
}
