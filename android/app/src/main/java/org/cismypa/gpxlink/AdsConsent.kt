package org.cismypa.gpxlink

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Runs the Google User Messaging Platform (UMP) flow when required (for example EEA users),
 * then invokes [onFinished] so the host can initialize the Mobile Ads SDK and load ads.
 */
fun requestAdsConsentThen(activity: Activity, onFinished: () -> Unit) {
    val consentInformation: ConsentInformation = UserMessagingPlatform.getConsentInformation(activity)
    val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
    consentInformation.requestConsentInfoUpdate(
        activity,
        params,
        {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _: FormError? ->
                onFinished()
            }
        },
        { _ ->
            onFinished()
        },
    )
}
