package org.cismypa.gpxlink

import android.app.Activity
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdsConsentTest {
    private class FailedConsentUpdate(private val adsAllowed: Boolean) : ConsentInformation {
        override fun getConsentStatus() = ConsentInformation.ConsentStatus.UNKNOWN
        override fun getPrivacyOptionsRequirementStatus() =
            ConsentInformation.PrivacyOptionsRequirementStatus.UNKNOWN
        override fun reset() {}
        override fun canRequestAds() = adsAllowed
        override fun isConsentFormAvailable() = false
        override fun requestConsentInfoUpdate(
            activity: Activity,
            params: ConsentRequestParameters,
            success: ConsentInformation.OnConsentInfoUpdateSuccessListener,
            failure: ConsentInformation.OnConsentInfoUpdateFailureListener,
        ) {
            failure.onConsentInfoUpdateFailure(FormError(1, "Network unavailable"))
        }
    }

    @Test
    fun failureUsesSdkPermissionIncludingAnyPriorConsent() {
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        for (allowed in listOf(false, true)) {
            val callbacks = mutableListOf<Boolean>()
            requestAdsConsentThen(activity, FailedConsentUpdate(allowed)) { callbacks.add(it) }
            assertEquals(listOf(allowed), callbacks)
        }
    }
}
