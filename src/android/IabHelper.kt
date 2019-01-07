package com.alexdisler.inapppurchases

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import com.alexdisler.inapppurchases.enums.ItemType
import java.util.ArrayList

abstract class IabHelper(protected val context: Context) {
  private var debugLog: Boolean = false
  private var debugTag: String = "IabHelper"

  // Has this object been disposed of? (If so, we should ignore callbacks, etc)
  protected var disposed = false

  // Is setup done?
  protected var setupDone = false

  // Can we skip the online purchase verification?
  // (Only allowed if the app is debuggable)
  public var skipPurchaseVerification = false

  // Are subscriptions supported?
  protected var subscriptionsSupported = false

  abstract fun launchPurchaseFlow(act: Activity, sku: String, itemType: ItemType, requestCode: Int, extraData: String, listener: (IabResult, Purchase?) -> Unit)

  abstract fun startSetup(listener: (IabResult) -> Unit)

  abstract fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent): Boolean

  /**
   * Enables or disable debug logging through LogCat.
   */
  fun enableDebugLogging(enable: Boolean, tag: String) {
    checkNotDisposed()
    debugLog = enable
    debugTag = tag
  }


  fun enableDebugLogging(enable: Boolean) {
    checkNotDisposed()
    debugLog = enable
  }

  fun dispose() {
    disposeInternal()
    setupDone = false
    disposed = true
  }

  abstract protected fun disposeInternal()

  fun launchPurchaseFlow(act: Activity, sku: String, requestCode: Int, listener: (IabResult, Purchase?) -> Unit){
    launchPurchaseFlow(act, sku, requestCode, "", listener)
  }

  fun launchPurchaseFlow(act: Activity, sku: String, requestCode: Int, extraData: String,
                         listener: (IabResult, Purchase?) -> Unit) {
    launchPurchaseFlow(act, sku, ItemType.INAPP, requestCode, extraData, listener)
  }

  fun launchSubscriptionPurchaseFlow(act: Activity, sku: String, requestCode: Int,
                                     listener: (IabResult, Purchase?) -> Unit) {
    launchSubscriptionPurchaseFlow(act, sku, requestCode, "", listener)
  }

  fun launchSubscriptionPurchaseFlow(act: Activity, sku: String, requestCode: Int, extraData: String,
                                     listener: (IabResult, Purchase?) -> Unit) {
    launchPurchaseFlow(act, sku, ItemType.SUBSCRIPTION, requestCode, extraData, listener)
  }

  abstract fun consumeAsync(purchase: Purchase, listener: (Purchase, IabResult) -> Unit)

  /**
   * Same as [consumeAsync], but for multiple items at once.
   * @param purchases The list of PurchaseInfo objects representing the purchases to consume.
   * @param listener The listener to notify when the consumption operation finishes.
   */
  abstract fun consumeAsync(purchases: List<Purchase>, listener: (List<Purchase>, List<IabResult>) -> Unit)

  abstract fun queryInventoryAsync(querySkuDetails: Boolean,
                          moreSkus: List<String>?,
                          listener: ((IabResult, Inventory) -> Unit)?)

  fun queryInventoryAsync(listener: (IabResult, Inventory) -> Unit) {
    queryInventoryAsync(true, null, listener)
  }

  fun queryInventoryAsync(querySkuDetails: Boolean, listener: (IabResult, Inventory) -> Unit) {
    queryInventoryAsync(querySkuDetails, null, listener)
  }

  @Throws(IabException::class)
  fun queryInventory(querySkuDetails: Boolean, moreSkus: List<String>): Inventory {
    return queryInventory(querySkuDetails, moreSkus, emptyList())
  }

  /**
   * Queries the inventory. This will query all owned items from the server, as well as
   * information on additional skus, if specified. This method may block or take long to execute.
   * Do not call from a UI thread. For that, use the non-blocking version {@link #refreshInventoryAsync}.
   *
   * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
   *     as purchase information.
   * @param moreItemSkus additional PRODUCT skus to query information on, regardless of ownership.
   *     Ignored if null or if querySkuDetails is false.
   * @param moreSubsSkus additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
   *     Ignored if null or if querySkuDetails is false.
   * @throws IabException if a problem occurs while refreshing the inventory.
   */
  @Throws(IabException::class)
  abstract fun queryInventory(querySkuDetails: Boolean, moreSkus: List<String>, moreSubsSkus: List<String>): Inventory

  /** Returns whether subscriptions are supported.  */
  fun subscriptionsSupported(): Boolean {
    checkNotDisposed()
    return subscriptionsSupported
  }

  protected fun checkNotDisposed() {
    if (disposed) throw IllegalStateException("IabHelper was disposed of, so it cannot be used.")
  }

  protected fun logDebug(msg: String) {
    if (debugLog) Log.d(debugTag, msg)
  }

  protected fun logError(msg: String) {
    Log.e(debugTag, "In-app billing error: $msg")
  }

  protected fun logWarn(msg: String) {
    Log.w(debugTag, "In-app billing warning: $msg")
  }

  /*
  /**
   * Callback for setup process. This listener's [.onIabSetupFinished] method is called
   * when the setup process is complete.
   */
  interface OnIabSetupFinishedListener {
    /**
     * Called to notify that setup is complete.
     *
     * @param result The result of the setup process.
     */
    fun onIabSetupFinished(result: IabResult)
  }

  /**
   * Callback that notifies when a purchase is finished.
   */
  interface OnIabPurchaseFinishedListener {
    /**
     * Called to notify that an in-app purchase finished. If the purchase was successful,
     * then the sku parameter specifies which item was purchased. If the purchase failed,
     * the sku and extraData parameters may or may not be null, depending on how far the purchase
     * process went.
     *
     * @param result The result of the purchase.
     * @param info The purchase information (null if purchase failed)
     */
    fun onIabPurchaseFinished(result: IabResult, info: Purchase?)
  }

  /**
   * Listener that notifies when an inventory query operation completes.
   */
  interface QueryInventoryFinishedListener {
    /**
     * Called to notify that an inventory query operation completed.
     *
     * @param result The result of the operation.
     * @param inv The inventory.
     */
    fun onQueryInventoryFinished(result: IabResult, inv: Inventory)
  }

  /**
   * Callback that notifies when a consumption operation finishes.
   */
  interface OnConsumeFinishedListener {
    /**
     * Called to notify that a consumption has finished.
     *
     * @param purchase The purchase that was (or was to be) consumed.
     * @param result The result of the consumption operation.
     */
    fun onConsumeFinished(purchase: Purchase, result: IabResult)
  }

  /**
   * Callback that notifies when a multi-item consumption operation finishes.
   */
  interface OnConsumeMultiFinishedListener {
    /**
     * Called to notify that a consumption of multiple items has finished.
     *
     * @param purchases The purchases that were (or were to be) consumed.
     * @param results The results of each consumption operation, corresponding to each
     * sku.
     */
    fun onConsumeMultiFinished(purchases: List<Purchase>, results: List<IabResult>)
  }
  */

  companion object {
    // Billing response codes
    val BILLING_RESPONSE_RESULT_OK = 0
    val BILLING_RESPONSE_RESULT_USER_CANCELED = 1
    val BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3
    val BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4
    val BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5
    val BILLING_RESPONSE_RESULT_ERROR = 6
    val BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7
    val BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8

    // IAB Helper error codes
    val IABHELPER_ERROR_BASE = -1000
    val IABHELPER_REMOTE_EXCEPTION = -1001
    val IABHELPER_BAD_RESPONSE = -1002
    val IABHELPER_VERIFICATION_FAILED = -1003
    val IABHELPER_SEND_INTENT_FAILED = -1004
    val IABHELPER_USER_CANCELLED = -1005
    val IABHELPER_UNKNOWN_PURCHASE_RESPONSE = -1006
    val IABHELPER_MISSING_TOKEN = -1007
    val IABHELPER_UNKNOWN_ERROR = -1008
    val IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE = -1009
    val IABHELPER_INVALID_CONSUMPTION = -1010

    /**
     * Returns a human-readable description for the given response code.
     *
     * @param code The response code
     * @return A human-readable string explaining the result code.
     * It also includes the result code numerically.
     */
    fun getResponseDesc(code: Int): String {
      val iab_msgs = ("0:OK/1:User Canceled/2:Unknown/" +
              "3:Billing Unavailable/4:Item unavailable/" +
              "5:Developer Error/6:Error/7:Item Already Owned/" +
              "8:Item not owned").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
      val iabhelper_msgs = ("0:OK/-1001:Remote exception during initialization/" +
              "-1002:Bad response received/" +
              "-1003:Purchase signature verification failed/" +
              "-1004:Send intent failed/" +
              "-1005:User cancelled/" +
              "-1006:Unknown purchase response/" +
              "-1007:Missing token/" +
              "-1008:Unknown error/" +
              "-1009:Subscriptions not available/" +
              "-1010:Invalid consumption attempt").split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

      if (code <= IabHelper.IABHELPER_ERROR_BASE) {
        val index = IabHelper.IABHELPER_ERROR_BASE - code
        return if (index >= 0 && index < iabhelper_msgs.size)
          iabhelper_msgs[index]
        else
          code.toString() + ":Unknown IAB Helper Error"
      } else return if (code < 0 || code >= iab_msgs.size)
        code.toString() + ":Unknown"
      else
        iab_msgs[code]
    }
  }
}

