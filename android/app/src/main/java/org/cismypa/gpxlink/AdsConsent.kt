package org.cismypa.gpxlink

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Runs the Google User Messaging Platform (UMP) flow when required (for example EEA users),
 * then invokes [onFinished] so the host can initialize the Mobile Ads SDK and load ads.
 *
 * UMP may call [ConsentInformation.requestConsentInfoUpdate] listeners off the main thread, while
 * [UserMessagingPlatform.loadAndShowConsentFormIfRequired] must run on the main thread; ad SDK
 * init is also expected on the main thread. This helper marshals those steps accordingly.
 */
fun requestAdsConsentThen(activity: Activity, onFinished: () -> Unit) {
    val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
    val finishIfAlive: () -> Unit = {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            onFinished()
        }
    }
    consentInformation.requestConsentInfoUpdate(
        activity,
        params,
        {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _: FormError? ->
                    finishIfAlive()
                }
            }
        },
        { _ ->
            finishIfAlive()
        },
    )
}
