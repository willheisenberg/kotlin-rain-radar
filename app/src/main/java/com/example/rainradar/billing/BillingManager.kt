package com.example.rainradar.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BillingManager private constructor(context: Context) {
    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_PREMIUM_WIDGET = "premium_widget"
        const val PREFS_NAME = "rain_radar_prefs"
        const val KEY_IS_PREMIUM = "is_premium"
        const val KEY_IS_PREMIUM_DEBUG = "is_premium_debug"

        @Volatile
        private var INSTANCE: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun isPremiumUser(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_IS_PREMIUM, false) || prefs.getBoolean(KEY_IS_PREMIUM_DEBUG, false)) {
                return true
            }
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val installTime = packageInfo.firstInstallTime
                val currentTime = System.currentTimeMillis()
                val trialDurationMs = 24 * 60 * 60 * 1000L // 24 hours
                (currentTime - installTime) < trialDurationMs
            } catch (e: Exception) {
                false
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPremium = MutableStateFlow(getIsPremiumLocal())
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isPremiumDebug = MutableStateFlow(prefs.getBoolean(KEY_IS_PREMIUM_DEBUG, false))
    val isPremiumDebug: StateFlow<Boolean> = _isPremiumDebug.asStateFlow()

    private val _productPrice = MutableStateFlow<String?>(null)
    val productPrice: StateFlow<String?> = _productPrice.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult: BillingResult, purchases: List<Purchase>? ->
        Log.d(TAG, "onPurchasesUpdated: responseCode = ${billingResult.responseCode}")
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.d(TAG, "Purchase canceled by user")
        } else {
            Log.e(TAG, "Purchase failed: ${billingResult.debugMessage}")
        }
    }

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        startConnection()
    }

    fun startConnection() {
        if (billingClient.isReady) return

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.d(TAG, "onBillingSetupFinished: responseCode = ${billingResult.responseCode}")
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _connectionState.value = true
                    queryPurchases()
                    queryProductDetails()
                } else {
                    _connectionState.value = false
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "onBillingServiceDisconnected")
                _connectionState.value = false
            }
        })
    }

    private fun getIsPremiumLocal(): Boolean {
        return isPremiumUser(appContext)
    }

    fun queryPurchases() {
        if (!billingClient.isReady) return

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            Log.d(TAG, "queryPurchasesAsync finished: code = ${billingResult.responseCode}, purchasesCount = ${purchasesList?.size ?: 0}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var premiumActive = false
                purchasesList?.forEach { purchase ->
                    if (purchase.products.contains(PRODUCT_PREMIUM_WIDGET) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        premiumActive = true
                        
                        // Acknowledge purchase if not acknowledged
                        if (!purchase.isAcknowledged) {
                            acknowledgePurchase(purchase.purchaseToken)
                        }
                    }
                }
                setPremiumStatus(premiumActive)
            }
        }
    }

    private fun acknowledgePurchase(purchaseToken: String) {
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
            Log.d(TAG, "acknowledgePurchase finished: code = ${billingResult.responseCode}")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.products.contains(PRODUCT_PREMIUM_WIDGET) &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            setPremiumStatus(true)
            if (!purchase.isAcknowledged) {
                acknowledgePurchase(purchase.purchaseToken)
            }
        }
    }

    private fun queryProductDetails() {
        if (!billingClient.isReady) return

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PREMIUM_WIDGET)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            Log.d(TAG, "queryProductDetailsAsync: code = ${billingResult.responseCode}, size = ${productDetailsList?.size ?: 0}")
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val premiumWidgetDetails = productDetailsList?.firstOrNull { it.productId == PRODUCT_PREMIUM_WIDGET }
                val price = premiumWidgetDetails?.oneTimePurchaseOfferDetails?.formattedPrice
                _productPrice.value = price
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        if (!billingClient.isReady) {
            Log.e(TAG, "BillingClient is not ready to launch purchase flow")
            startConnection()
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_PREMIUM_WIDGET)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val productDetails = productDetailsList?.firstOrNull { it.productId == PRODUCT_PREMIUM_WIDGET }
                if (productDetails != null) {
                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )

                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build()

                    billingClient.launchBillingFlow(activity, billingFlowParams)
                } else {
                    Log.e(TAG, "Product details not found for $PRODUCT_PREMIUM_WIDGET")
                }
            } else {
                Log.e(TAG, "Failed to fetch product details to launch flow: ${billingResult.debugMessage}")
            }
        }
    }

    fun setPremiumStatus(isPremium: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PREMIUM, isPremium).apply()
        _isPremium.value = getIsPremiumLocal()
        notifyWidgets()
    }

    fun toggleDeveloperBypass() {
        val nextVal = !prefs.getBoolean(KEY_IS_PREMIUM_DEBUG, false)
        prefs.edit().putBoolean(KEY_IS_PREMIUM_DEBUG, nextVal).apply()
        _isPremiumDebug.value = nextVal
        _isPremium.value = getIsPremiumLocal()
        notifyWidgets()
    }


    private fun notifyWidgets() {
        try {
            val intent = Intent(appContext, com.example.rainradar.widget.RadarWidgetProvider::class.java).apply {
                action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(appContext)
            val ids = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(appContext, com.example.rainradar.widget.RadarWidgetProvider::class.java)
            )
            if (ids.isNotEmpty()) {
                intent.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                appContext.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast update to widgets", e)
        }
    }
}
