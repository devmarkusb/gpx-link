package org.cismypa.gpxlink

import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration

/** AdMob’s documented emulator id for [RequestConfiguration.setTestDeviceIds](https://developers.google.com/admob/android/test-ads). */
private const val ADMOB_EMULATOR_TEST_DEVICE_ID =
    "B3EEABB8EE11C2BE7B444418D4BF457EE9ED90D510256E1E8E2C94" // pragma: allowlist secret

/**
 * Registers AdMob [test devices](https://developers.google.com/admob/android/test-ads) for **debug**
 * builds so clicks are not counted as invalid traffic. Always includes the emulator; add physical
 * device hashes via `gpxlink.debugAdmobTestDeviceIds` (comma-separated) in `android/gradle.properties`
 * or the `DEBUG_ADMOB_TEST_DEVICE_IDS` environment variable. Call **before** [MobileAds.initialize].
 */
fun applyAdMobDebugTestDeviceConfiguration() {
    if (!BuildConfig.DEBUG) return
    val extras =
        BuildConfig.DEBUG_ADMOB_TEST_DEVICE_IDS.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    val ids = buildList {
        add(ADMOB_EMULATOR_TEST_DEVICE_ID)
        addAll(extras)
    }
    MobileAds.setRequestConfiguration(
        RequestConfiguration.Builder()
            .setTestDeviceIds(ids)
            .build(),
    )
}
