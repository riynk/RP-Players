/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package app.riynk.rpp.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import app.riynk.rpp.R
import app.riynk.rpp.activities.DriveModeActivity
import app.riynk.rpp.activities.LicenseActivity
import app.riynk.rpp.activities.SupportDevelopmentActivity
import app.riynk.rpp.activities.WhatsNewFragment
import app.riynk.rpp.activities.bugreport.BugReportActivity
import app.riynk.rpp.extensions.showToast
import app.riynk.rpp.helper.MusicPlayerRemote.audioSessionId

object NavigationUtil {
    fun bugReport(activity: Activity) {
        activity.startActivity(
            Intent(activity, BugReportActivity::class.java), null
        )
    }

    fun goToOpenSource(activity: Activity) {
        activity.startActivity(
            Intent(activity, LicenseActivity::class.java), null
        )
    }

    fun goToSupportDevelopment(activity: Activity) {
        activity.startActivity(
            Intent(activity, SupportDevelopmentActivity::class.java), null
        )
    }

    fun gotoDriveMode(activity: Activity) {
        activity.startActivity(
            Intent(activity, DriveModeActivity::class.java), null
        )
    }

    fun gotoWhatNews(activity: FragmentActivity) {
        val changelogBottomSheet = WhatsNewFragment()
        changelogBottomSheet.show(activity.supportFragmentManager, WhatsNewFragment.TAG)
    }

    fun openEqualizer(activity: Activity) {
        stockEqualizer(activity)
    }

    private fun stockEqualizer(activity: Activity) {
        val sessionId = audioSessionId
        if (sessionId == AudioEffect.ERROR_BAD_VALUE) {
            activity.showToast(R.string.no_audio_ID, Toast.LENGTH_LONG)
        } else {
            try {
                val effects = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                effects.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                effects.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                activity.startActivityForResult(effects, 0)
            } catch (notFound: ActivityNotFoundException) {
                activity.showToast(R.string.no_equalizer)
            }
        }
    }
}