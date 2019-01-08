/* Copyright (c) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alexdisler.inapppurchases.playstore

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender.SendIntentException
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import android.text.TextUtils

import com.alexdisler.inapppurchases.IabException
import com.alexdisler.inapppurchases.IabHelper
import com.alexdisler.inapppurchases.IabResult
import com.alexdisler.inapppurchases.Inventory
import com.alexdisler.inapppurchases.Purchase
import com.alexdisler.inapppurchases.Security
import com.alexdisler.inapppurchases.SkuDetails
import com.alexdisler.inapppurchases.enums.ItemType
import com.android.vending.billing.IInAppBillingService
import org.apache.cordova.CordovaInterface

import org.json.JSONException

import java.util.ArrayList


/**
 * Provides convenience methods for in-app billing. You can create one instance of this
 * class for your application and use it to process in-app billing operations.
 * It provides synchronous (blocking) and asynchronous (non-blocking) methods for
 * many common in-app billing operations, as well as automatic signature
 * verification.
 *
 * After instantiating, you must perform setup in order to start using the object.
 * To perform setup, call the [.startSetup] method and provide a listener;
 * that listener will be notified when setup is complete, after which (and not before)
 * you may call other methods.
 *
 * After setup is complete, you will typically want to request an inventory of owned
 * items and subscriptions. See [.queryInventory], [.queryInventoryAsync]
 * and related methods.
 *
 * When you are done with this object, don't forget to call [.dispose]
 * to ensure proper cleanup. This object holds a binding to the in-app billing
 * service, which will leak unless you dispose of it correctly. If you created
 * the object on an Activity's onCreate method, then the recommended
 * place to dispose of it is the Activity's onDestroy method.
 *
 * A note about threading: When using this object from a background thread, you may
 * call the blocking versions of methods; when using from a UI thread, call
 * only the asynchronous versions and handle the results via callbacks.
 * Also, notice that you can only call one asynchronous operation at a time;
 * attempting to start a second asynchronous operation while the first one
 * has not yet completed will result in an exception being thrown.
 *
 * @author Bruno Oliveira (Google)
 */
class IabHelperImpl
/**
 * Creates an instance. After creation, it will not yet be ready to use. You must perform
 * setup by calling [.startSetup] and wait for setup to complete. This constructor does not
 * block and is safe to call from a UI thread.
 *
 * @param ctx Your application or Activity context. Needed to bind to the in-app billing service.
 */
(private val cordova: CordovaInterface) : IabHelper(cordova.activity) {
    // Is an asynchronous operation in progress?
    // (only one at a time can be in progress)
    private var mAsyncInProgress = false

    // (for logging/debugging)
    // if mAsyncInProgress == true, what asynchronous operation is in progress?
    private var mAsyncOperation = ""

    // Connection to the service
    internal var mService: IInAppBillingService? = null
    private var mServiceConn: ServiceConnection? = null

    // The request code used to launch purchase flow
    private var mRequestCode: Int = 0

    // The item type of the current purchase flow
    private lateinit var mPurchasingItemType: ItemType

    // Public key for verifying signature, in base64 encoding
    private var mSignatureBase64: String? = null


    // The listener registered on launchPurchaseFlow, which we have to call back when
    // the purchase finishes
    private var mPurchaseListener: ((IabResult, info: Purchase?) -> Unit)? = null

    init {
        val appInfo = context.applicationContext.packageManager.getApplicationInfo(context.applicationContext.packageName, PackageManager.GET_META_DATA)
        if (appInfo != null) {
            mSignatureBase64 = appInfo.metaData.getString(PLAYSTORE_KEY)
        }
        logDebug("IAB helper created.")
    }

    /**
     * Starts the setup process. This will start up the setup process asynchronously.
     * You will be notified through the listener when the setup process is complete.
     * This method is safe to call from a UI thread.
     *
     * @param listener The listener to notify when the setup process is complete.
     */
    override fun startSetup(listener: (IabResult) -> Unit) {
        // If already set up, can't do it again.
        checkNotDisposed()
        if (setupDone) throw IllegalStateException("IAB helper is already set up.")

        // Connection to IAB service
        logDebug("Starting in-app billing setup.")
        mServiceConn = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName) {
                logDebug("Billing service disconnected.")
                mService = null
            }

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (disposed) return
                logDebug("Billing service connected.")
                mService = IInAppBillingService.Stub.asInterface(service)
                val packageName = context.applicationContext.packageName
                try {
                    logDebug("Checking for in-app billing 3 support.")

                    // check for in-app billing v3 support
                    var response = mService!!.isBillingSupported(3, packageName, ItemType.INAPP.value)
                    if (response != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                        listener(IabResult(response,
                                "Error checking for billing v3 support."))

                        // if in-app purchases aren't supported, neither are subscriptions.
                        subscriptionsSupported = false
                        return
                    }
                    logDebug("In-app billing version 3 supported for $packageName")

                    // check for v3 subscriptions support
                    response = mService!!.isBillingSupported(3, packageName, ItemType.SUBSCRIPTION.value)
                    if (response == IabHelper.BILLING_RESPONSE_RESULT_OK) {
                        logDebug("Subscriptions AVAILABLE.")
                        subscriptionsSupported = true
                    } else {
                        logDebug("Subscriptions NOT AVAILABLE. Response: $response")
                    }

                    setupDone = true
                } catch (e: RemoteException) {
                    listener(IabResult(IABHELPER_REMOTE_EXCEPTION,
                            "RemoteException while setting up in-app billing."))
                    e.printStackTrace()
                    return
                }

                listener(IabResult(BILLING_RESPONSE_RESULT_OK, "Setup successful."))
            }
        }

        val serviceIntent = Intent("com.android.vending.billing.InAppBillingService.BIND")
        serviceIntent.setPackage("com.android.vending")
        if (!context.applicationContext.packageManager.queryIntentServices(serviceIntent, 0).isEmpty()) {
            // service available to handle that Intent
            context.applicationContext.bindService(serviceIntent, mServiceConn!!, Context.BIND_AUTO_CREATE)
        } else {
            // no service available to handle that Intent
            listener(
                    IabResult(IabHelper.BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE,
                            "Billing service unavailable on device."))
        }
    }

    /**
     * Dispose of object, releasing resources. It's very important to call this
     * method when you are done with this object. It will release any resources
     * used by it such as service connections. Naturally, once the object is
     * disposed of, it can't be used again.
     */
    override fun disposeInternal() {
        logDebug("Disposing.")
        if (mServiceConn != null) {
            logDebug("Unbinding from service.")
            context.applicationContext.unbindService(mServiceConn!!)
        }

        mServiceConn = null
        mService = null
        mPurchaseListener = null
    }

    /**
     * Initiate the UI flow for an in-app purchase. Call this method to initiate an in-app purchase,
     * which will involve bringing up the Google Play screen. The calling activity will be paused while
     * the user interacts with Google Play, and the result will be delivered via the activity's
     * [android.app.Activity.onActivityResult] method, at which point you must call
     * this object's [.handleActivityResult] method to continue the purchase flow. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param act The calling activity.
     * @param sku The sku of the item to purchase.
     * @param itemType indicates if it's a product or a subscription (ItemType.INAPP or ITEM_TYPE_SUBS)
     * @param requestCode A request code (to differentiate from other responses --
     * as in [android.app.Activity.startActivityForResult]).
     * @param listener The listener to notify when the purchase process finishes
     * @param extraData Extra data (developer payload), which will be returned with the purchase data
     * when the purchase completes. This extra data will be permanently bound to that purchase
     * and will always be returned when the purchase is queried.
     */
    override fun launchPurchaseFlow(act: Activity, sku: String, itemType: ItemType, requestCode: Int,
                                    extraData: String, listener: (IabResult, Purchase?) -> Unit) {
        checkNotDisposed()
        checkSetupDone("launchPurchaseFlow")
        flagStartAsync("launchPurchaseFlow")
        var result: IabResult

        if (itemType == ItemType.SUBSCRIPTION && !subscriptionsSupported) {
            val r = IabResult(IabHelper.IABHELPER_SUBSCRIPTIONS_NOT_AVAILABLE,
                    "Subscriptions are not available.")
            flagEndAsync()
            listener(r, null)
            return
        }

        try {
            logDebug("Constructing buy intent for $sku, item type: $itemType")
            val buyIntentBundle = mService!!.getBuyIntent(3, context.applicationContext.packageName, sku, itemType.value, extraData)
            val response = getResponseCodeFromBundle(buyIntentBundle)
            if (response != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                logError("Unable to buy item, Error response: " + getResponseDesc(response))
                flagEndAsync()
                result = IabResult(response, "Unable to buy item")
                listener(result, null)
                return
            }

            val pendingIntent = buyIntentBundle.getParcelable<PendingIntent>(RESPONSE_BUY_INTENT)
            logDebug("Launching buy intent for $sku. Request code: $requestCode")
            mRequestCode = requestCode
            mPurchaseListener = listener
            mPurchasingItemType = itemType
            act.startIntentSenderForResult(pendingIntent!!.intentSender,
                    requestCode, Intent(),
                    0, 0,
                    0)
        } catch (e: SendIntentException) {
            logError("SendIntentException while launching purchase flow for sku $sku")
            e.printStackTrace()
            flagEndAsync()

            result = IabResult(IabHelper.IABHELPER_SEND_INTENT_FAILED, "Failed to send intent.")
            listener(result, null)
        } catch (e: RemoteException) {
            logError("RemoteException while launching purchase flow for sku $sku")
            e.printStackTrace()
            flagEndAsync()

            result = IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION, "Remote exception while starting purchase flow")
            listener(result, null)
        }

    }

    /**
     * Handles an activity result that's part of the purchase flow in in-app billing. If you
     * are calling [.launchPurchaseFlow], then you must call this method from your
     * Activity's [@onActivityResult][android.app.Activity] method. This method
     * MUST be called from the UI thread of the Activity.
     *
     * @param requestCode The requestCode as you received it.
     * @param resultCode The resultCode as you received it.
     * @param data The data (Intent) as you received it.
     * @return Returns true if the result was related to a purchase flow and was handled;
     * false if the result was not related to a purchase, in which case you should
     * handle it normally.
     */
    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        var result: IabResult
        if (requestCode != mRequestCode) return false

        checkNotDisposed()
        checkSetupDone("handleActivityResult")

        // end of async purchase operation that started on launchPurchaseFlow
        flagEndAsync()

        if (data == null) {
            logError("Null data in IAB activity result.")
            result = IabResult(IabHelper.IABHELPER_BAD_RESPONSE, "Null data in IAB result")
            if (mPurchaseListener != null) mPurchaseListener!!(result, null)
            return true
        }

        val responseCode = getResponseCodeFromIntent(data)
        val purchaseData = data.getStringExtra(RESPONSE_INAPP_PURCHASE_DATA)
        val dataSignature = data.getStringExtra(RESPONSE_INAPP_SIGNATURE)

        if (resultCode == Activity.RESULT_OK && responseCode == IabHelper.BILLING_RESPONSE_RESULT_OK) {
            logDebug("Successful resultcode from purchase activity.")
            logDebug("Purchase data: " + purchaseData!!)
            logDebug("Data signature: " + dataSignature!!)
            logDebug("Extras: " + data.extras!!)
            logDebug("Expected item type: $mPurchasingItemType")

            if (purchaseData == null || dataSignature == null) {
                logError("BUG: either purchaseData or dataSignature is null.")
                logDebug("Extras: " + data.extras!!.toString())
                result = IabResult(IabHelper.IABHELPER_UNKNOWN_ERROR, "IAB returned null purchaseData or dataSignature")
                if (mPurchaseListener != null) mPurchaseListener!!(result, null)
                return true
            }

            var purchase: Purchase?
            try {
                purchase = Purchase.apply(mPurchasingItemType, dataSignature, purchaseData)

                val sku = purchase.sku
                // Only allow purchase verification to be skipped if we are debuggable
                val skipPurchaseVerification = skipPurchaseVerification && context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                // Verify signature
                if (!skipPurchaseVerification) {
                    if (!Security.verifyPurchase(mSignatureBase64, purchaseData, dataSignature)) {
                        logError("Purchase signature verification FAILED for sku $sku")
                        result = IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED, "Signature verification failed for sku $sku")
                        mPurchaseListener?.invoke(result, purchase)
                        return true
                    }
                    logDebug("Purchase signature successfully verified.")
                }
            } catch (e: JSONException) {
                logError("Failed to parse purchase data.")
                e.printStackTrace()
                result = IabResult(IabHelper.IABHELPER_BAD_RESPONSE, "Failed to parse purchase data.")

                mPurchaseListener?.invoke(result, null)
                return true
            }

            mPurchaseListener?.invoke(IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Success"), purchase)
        } else if (resultCode == Activity.RESULT_OK) {
            // result code was OK, but in-app billing response was not OK.
            logDebug("Result code was OK but in-app billing response was not OK: " + getResponseDesc(responseCode))
            result = IabResult(responseCode, "Problem purchashing item.")
            mPurchaseListener?.invoke(result, null)
        } else if (resultCode == Activity.RESULT_CANCELED) {
            logDebug("Purchase canceled - Response: " + getResponseDesc(responseCode))
            result = IabResult(IabHelper.IABHELPER_USER_CANCELLED, "User canceled.")
            mPurchaseListener?.invoke(result, null)
        } else {
            logError("Purchase failed. Result code: " + Integer.toString(resultCode)
                    + ". Response: " + getResponseDesc(responseCode))
            result = IabResult(IabHelper.IABHELPER_UNKNOWN_PURCHASE_RESPONSE, "Unknown purchase response.")
            mPurchaseListener?.invoke(result, null)
        }
        return true
    }

    /**
     * Queries the inventory. This will query all owned items from the server, as well as
     * information on additional skus, if specified. This method may block or take long to execute.
     * Do not call from a UI thread. For that, use the non-blocking version [.refreshInventoryAsync].
     *
     * @param querySkuDetails if true, SKU details (price, description, etc) will be queried as well
     * as purchase information.
     * @param moreItemSkus additional PRODUCT skus to query information on, regardless of ownership.
     * Ignored if null or if querySkuDetails is false.
     * @param moreSubsSkus additional SUBSCRIPTIONS skus to query information on, regardless of ownership.
     * Ignored if null or if querySkuDetails is false.
     * @throws IabException if a problem occurs while refreshing the inventory.
     */
    /*
    @Throws(IabException::class)
    private fun queryInventory(querySkuDetails: Boolean, moreItemSkus: List<String>?,
                               moreSubsSkus: List<String> = emptyList()): Inventory {
        checkNotDisposed()
        checkSetupDone("queryInventory")
        try {
            val inv = Inventory()
            var r = queryPurchases(inv, ItemType.INAPP)
            if (r != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                throw IabException(r, "Error refreshing inventory (querying owned items).")
            }

            if (querySkuDetails) {
                r = querySkuDetails(ItemType.INAPP, inv, moreItemSkus)
                if (r != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                    throw IabException(r, "Error refreshing inventory (querying prices of items).")
                }
            }

            // if subscriptions are supported, then also query for subscriptions
            if (subscriptionsSupported && moreSubsSkus.isNotEmpty()) {
                r = queryPurchases(inv, ItemType.SUBSCRIPTION)
                if (r != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                    throw IabException(r, "Error refreshing inventory (querying owned subscriptions).")
                }

                if (querySkuDetails) {
                    r = querySkuDetails(ItemType.SUBSCRIPTION, inv, moreSubsSkus)
                    if (r != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                        throw IabException(r, "Error refreshing inventory (querying prices of subscriptions).")
                    }
                }
            }

            return inv
        } catch (e: RemoteException) {
            throw IabException(IabHelper.IABHELPER_REMOTE_EXCEPTION, "Remote exception while refreshing inventory.", e)
        } catch (e: JSONException) {
            throw IabException(IabHelper.IABHELPER_BAD_RESPONSE, "Error parsing JSON response while refreshing inventory.", e)
        }

    }
    */


    /**
     * Asynchronous wrapper for inventory query. This will perform an inventory
     * query as described in [.queryInventory], but will do so asynchronously
     * and call back the specified listener upon completion. This method is safe to
     * call from a UI thread.
     *
     * @param querySkuDetails as in [.queryInventory]
     * @param moreSkus as in [.queryInventory]
     * @param listener The listener to notify when the refresh operation completes.
     */
    override fun queryInventoryAsync(
                                     moreSkus: List<String>,
                                     listener: (IabResult, Inventory?) -> Unit) {
        val handler = Handler()
        checkNotDisposed()
        checkSetupDone("queryInventory")
        flagStartAsync("refresh inventory")
        cordova.threadPool.execute {
            val inv: Inventory = Inventory()
            val r = querySkuDetails(ItemType.INAPP, inv, moreSkus)
            val result = when(r) {
                IabHelper.BILLING_RESPONSE_RESULT_OK ->
                    IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.")
                else -> {
                    IabResult(r, "Error refreshing inventory (querying prices of items).")
                }
            }

            flagEndAsync()

            handler.post { listener(result, inv) }

        }
    }

    override fun queryPurchasesAsync(listener: (IabResult, List<Purchase>?) -> Unit) {
        val handler = Handler()
        checkNotDisposed()
        checkSetupDone("queryPurchase")
        flagStartAsync("query purchase")

        cordova.threadPool.execute {
            val inv = Inventory()
            val r = queryPurchases(inv, ItemType.INAPP)

            val result = when(r) {
                IabHelper.BILLING_RESPONSE_RESULT_OK ->
                    IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Inventory refresh successful.")
                else -> {
                    IabResult(r, "Error refreshing inventory (querying prices of items).")
                }
            }

            flagEndAsync()
            handler.post { listener(result, inv.allPurchases)}
        }
    }

    /**
     * Consumes a given in-app product. Consuming can only be done on an item
     * that's owned, and as a result of consumption, the user will no longer own it.
     * This method may block or take long to return. Do not call from the UI thread.
     * For that, see [.consumeAsync].
     *
     * @param itemInfo The PurchaseInfo that represents the item to consume.
     * @throws IabException if there is a problem during consumption.
     */
    @Throws(IabException::class)
    internal fun consume(itemInfo: Purchase) {
        checkNotDisposed()
        checkSetupDone("consume")

        if (itemInfo.itemType != ItemType.INAPP) {
            throw IabException(IabHelper.IABHELPER_INVALID_CONSUMPTION,
                    "Items of type '" + itemInfo.itemType + "' can't be consumed.")
        }

        try {
            val token = itemInfo.token
            val sku = itemInfo.sku
            if (token.isNullOrEmpty()) {
                logError("Can't consume $sku. No token.")
                throw IabException(IabHelper.IABHELPER_MISSING_TOKEN, "PurchaseInfo is missing token for sku: "
                        + sku + " " + itemInfo)
            }

            logDebug("Consuming sku: $sku, token: $token")
            val response = mService!!.consumePurchase(3, context.applicationContext.packageName, token)
            if (response == IabHelper.BILLING_RESPONSE_RESULT_OK) {
                logDebug("Successfully consumed sku: $sku")
            } else {
                logDebug("Error consuming consuming sku " + sku + ". " + getResponseDesc(response))
                throw IabException(response, "Error consuming sku $sku")
            }
        } catch (e: RemoteException) {
            throw IabException(IabHelper.IABHELPER_REMOTE_EXCEPTION, "Remote exception while consuming. PurchaseInfo: $itemInfo", e)
        }

    }


    /**
     * Asynchronous wrapper to item consumption. Works like [.consume], but
     * performs the consumption in the background and notifies completion through
     * the provided listener. This method is safe to call from a UI thread.
     *
     * @param purchase The purchase to be consumed.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    override fun consumeAsync(purchase: Purchase, listener: (Purchase, IabResult) -> Unit) {
        checkNotDisposed()
        checkSetupDone("consume")
        val purchases = ArrayList<Purchase>()
        purchases.add(purchase)
        consumeAsyncInternal(purchases, listener, null)
    }

    /**
     * Same as [consumeAsync], but for multiple items at once.
     * @param purchases The list of PurchaseInfo objects representing the purchases to consume.
     * @param listener The listener to notify when the consumption operation finishes.
     */
    /*
    override fun consumeAsync(purchases: List<Purchase>, listener: (List<Purchase>, List<IabResult>) -> Unit) {
        checkNotDisposed()
        checkSetupDone("consume")
        consumeAsyncInternal(purchases, null, listener)
    }
    */


    // Workaround to bug where sometimes response codes come as Long instead of Integer
    private fun getResponseCodeFromBundle(b: Bundle): Int {
        val o = b.get(RESPONSE_CODE)
        return when (o) {
            null -> {
                logDebug("Bundle with null response code, assuming OK (known issue)")
                IabHelper.BILLING_RESPONSE_RESULT_OK
            }
            is Int -> o.toInt()
            is Long -> o.toLong().toInt()
            else -> {
                logError("Unexpected type for bundle response code.")
                logError(o.javaClass.name)
                throw RuntimeException("Unexpected type for bundle response code: " + o.javaClass.name)
            }
        }
    }

    // Workaround to bug where sometimes response codes come as Long instead of Integer
    private fun getResponseCodeFromIntent(i: Intent): Int {
        val o = i.extras!!.get(RESPONSE_CODE)
        return when (o) {
            null -> {
                logError("Intent with no response code, assuming OK (known issue)")
                IabHelper.BILLING_RESPONSE_RESULT_OK
            }
            is Int -> o.toInt()
            is Long -> o.toLong().toInt()
            else -> {
                logError("Unexpected type for intent response code.")
                logError(o.javaClass.name)
                throw RuntimeException("Unexpected type for intent response code: " + o.javaClass.name)
            }
        }
    }

    private fun flagStartAsync(operation: String) {
        if (mAsyncInProgress)
            throw IllegalStateException("Can't start async operation (" +
                    operation + ") because another async operation(" + mAsyncOperation + ") is in progress.")
        mAsyncOperation = operation
        mAsyncInProgress = true
        logDebug("Starting async operation: $operation")
    }

    private fun flagEndAsync() {
        logDebug("Ending async operation: $mAsyncOperation")
        mAsyncOperation = ""
        mAsyncInProgress = false
    }


    @Throws(JSONException::class, RemoteException::class)
    internal fun queryPurchases(inv: Inventory, itemType: ItemType): Int {
        // Query purchases
        logDebug("Querying owned items, item type: $itemType")
        logDebug("Package name: " + context.applicationContext.packageName)
        var verificationFailed = false
        // Only allow purchase verification to be skipped if we are debuggable
        val skipPurchaseVerification = skipPurchaseVerification && context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        var continueToken: String? = null

        do {
            logDebug("Calling getPurchases with continuation token: " + continueToken!!)
            val ownedItems = mService!!.getPurchases(3, context.applicationContext.packageName,
                    itemType.value, continueToken)

            val response = getResponseCodeFromBundle(ownedItems)
            logDebug("Owned items response: " + response.toString())
            if (response != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                logDebug("getPurchases() failed: " + getResponseDesc(response))
                return response
            }
            if (!ownedItems.containsKey(RESPONSE_INAPP_ITEM_LIST)
                    || !ownedItems.containsKey(RESPONSE_INAPP_PURCHASE_DATA_LIST)
                    || !ownedItems.containsKey(RESPONSE_INAPP_SIGNATURE_LIST)) {
                logError("Bundle returned from getPurchases() doesn't contain required fields.")
                return IabHelper.IABHELPER_BAD_RESPONSE
            }

            val ownedSkus = ownedItems.getStringArrayList(
                    RESPONSE_INAPP_ITEM_LIST)
            val purchaseDataList = ownedItems.getStringArrayList(
                    RESPONSE_INAPP_PURCHASE_DATA_LIST)
            val signatureList = ownedItems.getStringArrayList(
                    RESPONSE_INAPP_SIGNATURE_LIST)

            for (i in purchaseDataList!!.indices) {
                val purchaseData = purchaseDataList[i]
                val signature = signatureList!![i]
                val sku = ownedSkus!![i]
                if (skipPurchaseVerification || Security.verifyPurchase(mSignatureBase64, purchaseData, signature)) {
                    logDebug("Sku is owned: $sku")
                    val purchase = Purchase.apply(itemType, signature, purchaseData)

                    if (TextUtils.isEmpty(purchase.token)) {
                        logWarn("BUG: empty/null token!")
                        logDebug("Purchase data: $purchaseData")
                    }

                    // Record ownership and token
                    inv.addPurchase(purchase)
                } else {
                    logWarn("Purchase signature verification **FAILED**. Not adding item.")
                    logDebug("   Purchase data: $purchaseData")
                    logDebug("   Signature: $signature")
                    verificationFailed = true
                }
            }

            continueToken = ownedItems.getString(INAPP_CONTINUATION_TOKEN)
            logDebug("Continuation token: " + continueToken!!)
        } while (!TextUtils.isEmpty(continueToken))

        return if (verificationFailed) IabHelper.IABHELPER_VERIFICATION_FAILED else IabHelper.BILLING_RESPONSE_RESULT_OK
    }

    @Throws(RemoteException::class, JSONException::class)
    internal fun querySkuDetails(itemType: ItemType, inv: Inventory, moreSkus: List<String>?): Int {
        logDebug("Querying SKU details.")
        val skuList = ArrayList(inv.getAllOwnedSkus(itemType))
        if (moreSkus != null) {
            for (sku in moreSkus) {
                if (!skuList.contains(sku)) {
                    skuList.add(sku)
                }
            }
        }

        if (skuList.size == 0) {
            logDebug("queryPrices: nothing to do because there are no SKUs.")
            return IabHelper.BILLING_RESPONSE_RESULT_OK
        }

        val querySkus = Bundle()
        querySkus.putStringArrayList(GET_SKU_DETAILS_ITEM_LIST, skuList)
        val skuDetails = mService!!.getSkuDetails(3, context.applicationContext.packageName,
                itemType.value, querySkus)

        if (!skuDetails.containsKey(RESPONSE_GET_SKU_DETAILS_LIST)) {
            val response = getResponseCodeFromBundle(skuDetails)
            return if (response != IabHelper.BILLING_RESPONSE_RESULT_OK) {
                logDebug("getSkuDetails() failed: " + getResponseDesc(response))
                response
            } else {
                logError("getSkuDetails() returned a bundle with neither an error nor a detail list.")
                IabHelper.IABHELPER_BAD_RESPONSE
            }
        }

        val responseList = skuDetails.getStringArrayList(
                RESPONSE_GET_SKU_DETAILS_LIST)

        for (thisResponse in responseList!!) {
            val d = SkuDetails.initFromPlayStore(itemType, thisResponse)
            logDebug("Got sku details: $d")
            inv.addSkuDetails(d)
        }
        return IabHelper.BILLING_RESPONSE_RESULT_OK
    }


    private fun consumeAsyncInternal(purchases: List<Purchase>,
                                     singleListener: ((Purchase, IabResult) -> Unit)?,
                                     multiListener: ((List<Purchase>, List<IabResult>) -> Unit)?) {
        val handler = Handler()
        flagStartAsync("consume")
        Thread {
            val results = ArrayList<IabResult>()
            for (purchase in purchases) {
                try {
                    consume(purchase)
                    results.add(IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK, "Successful consume of sku " + purchase.sku))
                } catch (ex: IabException) {
                    results.add(ex.result)
                }

            }

            flagEndAsync()
            if (!disposed && singleListener != null) {
                handler.post { singleListener(purchases[0], results[0]) }
            }
            if (!disposed && multiListener != null) {
                handler.post { multiListener(purchases, results) }
            }
        }.start()
    }

    companion object {

        // Keys for the responses from InAppBillingService
        const val RESPONSE_CODE = "RESPONSE_CODE"
        const val RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST"
        const val RESPONSE_BUY_INTENT = "BUY_INTENT"
        const val RESPONSE_INAPP_PURCHASE_DATA = "INAPP_PURCHASE_DATA"
        const val RESPONSE_INAPP_SIGNATURE = "INAPP_DATA_SIGNATURE"
        const val RESPONSE_INAPP_ITEM_LIST = "INAPP_PURCHASE_ITEM_LIST"
        const val RESPONSE_INAPP_PURCHASE_DATA_LIST = "INAPP_PURCHASE_DATA_LIST"
        const val RESPONSE_INAPP_SIGNATURE_LIST = "INAPP_DATA_SIGNATURE_LIST"
        const val INAPP_CONTINUATION_TOKEN = "INAPP_CONTINUATION_TOKEN"

        // some fields on the getSkuDetails response bundle
        const val GET_SKU_DETAILS_ITEM_LIST = "ITEM_ID_LIST"
        const val GET_SKU_DETAILS_ITEM_TYPE_LIST = "ITEM_TYPE_LIST"

        const val PLAYSTORE_KEY = "play_store_key"
    }


}
