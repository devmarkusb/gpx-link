package org.cismypa.gpxlink

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * One-time in-app purchase to remove ads. [prefsName] should match the app preferences name
 * so [isAdFreeCached] stays consistent with the rest of the app.
 */
class RemoveAdsBilling(
    private val activity: Activity,
    private val productId: String,
    private val prefsName: String,
    private val onAdFreeChanged: (Boolean) -> Unit,
) : PurchasesUpdatedListener {

    companion object {
        const val PREF_KEY_AD_FREE = "ad_free_owned_v1"
    }

    private var billingClient: BillingClient? = null
    private var pendingProductDetails: ProductDetails? = null

    private fun prefs() = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun isAdFreeCached(): Boolean = prefs().getBoolean(PREF_KEY_AD_FREE, false)

    fun start() {
        if (billingClient?.isReady == true) {
            syncOwnedPurchases()
            return
        }
        val client =
            BillingClient.newBuilder(activity)
                .setListener(this)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
                )
                .build()
        billingClient = client
        client.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                        return
                    }
                    syncOwnedPurchases()
                    prefetchProductDetails()
                }

                override fun onBillingServiceDisconnected() {
                    pendingProductDetails = null
                }
            },
        )
    }

    fun close() {
        billingClient?.endConnection()
        billingClient = null
        pendingProductDetails = null
    }

    fun refreshOwnedPurchases() {
        syncOwnedPurchases()
    }

    private fun prefetchProductDetails() {
        val client = billingClient ?: return
        val product =
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            pendingProductDetails = list.firstOrNull()
        }
    }

    fun launchRemoveAdsPurchase() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val details = pendingProductDetails
        if (details != null) {
            launchWithDetails(client, details)
            return
        }
        val product =
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val pd = list.firstOrNull() ?: return@queryProductDetailsAsync
            pendingProductDetails = pd
            activity.runOnUiThread { launchWithDetails(client, pd) }
        }
    }

    private fun launchWithDetails(client: BillingClient, details: ProductDetails) {
        val productParams =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        val flowParams =
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
        client.launchBillingFlow(activity, flowParams)
    }

    private fun syncOwnedPurchases() {
        val client = billingClient ?: return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                return@queryPurchasesAsync
            }
            applyPurchasesList(purchases)
        }
    }

    private fun applyPurchasesList(purchases: List<Purchase>) {
        val owned =
            purchases.any { purchase ->
                purchase.products.contains(productId) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }
        if (owned) {
            purchases
                .filter { it.products.contains(productId) && !it.isAcknowledged }
                .forEach { acknowledgeIfNeeded(it) }
        }
        persistAndNotify(owned)
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                syncOwnedPurchases()
            }
        }
    }

    private fun persistAndNotify(adFree: Boolean) {
        prefs().edit().putBoolean(PREF_KEY_AD_FREE, adFree).apply()
        activity.runOnUiThread { onAdFreeChanged(adFree) }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    applyPurchasesList(purchases)
                } else {
                    syncOwnedPurchases()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> syncOwnedPurchases()
        }
    }
}
