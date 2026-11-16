package app.inkphone

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
 * The paid side of InkPhone: everything works for a 7-day trial, then the
 * extras lock until a single purchase unlocks them for good. The plain home
 * screen always keeps working, so a phone using InkPhone as its launcher is
 * never left stuck.
 *
 * Extras: doodle and grey icons, app limits, widgets, most-used apps, paper wash.
 */
object Pro {
    /** The one-time in-app product, created under this exact ID in Play Console. */
    const val PRODUCT_ID = "inkphone_unlock"
    const val TRIAL_DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val TRIAL_START = "trial_start"
    private const val OWNED = "pro_owned"

    /** Starts the trial clock the first time the app is opened. */
    fun touch(c: Context) {
        val sp = Prefs(c).sp
        if (!sp.contains(TRIAL_START)) sp.edit().putLong(TRIAL_START, System.currentTimeMillis()).apply()
    }

    fun owned(c: Context): Boolean = Prefs(c).sp.getBoolean(OWNED, false)

    internal fun setOwned(c: Context, owned: Boolean) = Prefs(c).sp.edit().putBoolean(OWNED, owned).apply()

    /** Whole days of trial remaining, 0 once it is over. */
    fun trialDaysLeft(c: Context): Int {
        val start = Prefs(c).sp.getLong(TRIAL_START, System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - start
        // A clock set backwards must not extend the trial for ever.
        if (elapsed < -DAY_MS) return 0
        val left = TRIAL_DAYS * DAY_MS - elapsed
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt().coerceAtMost(TRIAL_DAYS)
    }

    /** The icon look to draw with: the chosen one while unlocked, plain icons otherwise. */
    fun iconStyle(c: Context, prefs: Prefs): Int = if (unlocked(c)) prefs.iconStyle else IconStyler.ORIGINAL

    /** True while the extras may be used: bought, still in trial, or a test build. */
    fun unlocked(c: Context): Boolean = BuildConfig.DEBUG || owned(c) || trialDaysLeft(c) > 0
}

/** Thin wrapper over Google Play Billing for the single unlock product. */
class Billing(private val activity: Activity, private val onChange: () -> Unit) {
    /** Price as Google formats it for the user's country, once known. */
    var price: String? = null
        private set
    private var details: ProductDetails? = null

    private val purchases = PurchasesUpdatedListener { result, list ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && list != null) handle(list)
    }

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(purchases)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        runCatching {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                    loadProduct()
                    restore()
                }

                override fun onBillingServiceDisconnected() {}
            })
        }
    }

    fun stop() {
        runCatching { client.endConnection() }
    }

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(Pro.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        client.queryProductDetailsAsync(params) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = found.productDetailsList.firstOrNull()
            price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            activity.runOnUiThread(onChange)
        }
    }

    /** Asks Google Play what this account already owns; also how a reinstall gets its purchase back. */
    fun restore() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        runCatching {
            client.queryPurchasesAsync(params) { result, list ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(list, authoritative = true)
            }
        }
    }

    /** Opens Google Play's purchase sheet. Returns false if the product is not available yet. */
    fun buy(): Boolean {
        val product = details ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).build())
            )
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handle(list: List<Purchase>, authoritative: Boolean = false) {
        val mine = list.filter { Pro.PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val before = Pro.owned(activity)
        // Only a full query may take ownership away (for example after a refund).
        if (mine.isNotEmpty()) Pro.setOwned(activity, true) else if (authoritative) Pro.setOwned(activity, false)
        // Google refunds purchases that are not acknowledged within three days.
        for (purchase in mine.filter { !it.isAcknowledged }) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            ) { }
        }
        if (Pro.owned(activity) != before) activity.runOnUiThread(onChange)
    }
}
