// Copyright (c) 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/**
 *
 * Modifications: Alex Disler (alexdisler.com)
 * github.com/alexdisler/cordova-plugin-inapppurchase
 *
 */

package com.alexdisler.inapppurchases

import java.io.IOException
import java.io.InputStream
import java.util.ArrayList
import java.util.Scanner
import java.util.concurrent.atomic.AtomicInteger

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaWebView

import com.alexdisler.inapppurchases.enums.Store

import android.content.Intent
import android.util.Log
import com.alexdisler.inapppurchases.enums.ItemType

open class InAppBillingV3 : CordovaPlugin() {

    private var iabHelper: IabHelper? = null

    private var store: Store? = null

    private var billingInitialized = false
    private var orderSerial = AtomicInteger(0)

    private fun shouldSkipPurchaseVerification(): Boolean {
        return false
    }

    private fun initializeBillingHelper(): Boolean {
        if (iabHelper != null) {
            Log.d(TAG, "Billing already initialized")
            return true
        }


        val context = this.cordova.activity
        val skipPurchaseVerification = shouldSkipPurchaseVerification()

        iabHelper = when(store) {
            Store.ONESTORE -> com.alexdisler.inapppurchases.onestore.IabHelperImpl(cordova)
            Store.GOOGLE -> com.alexdisler.inapppurchases.playstore.IabHelperImpl(cordova)
            else -> {
                Log.d(TAG, "Unable to initialize billing store: ${store}")
                return false
            }
        }

        iabHelper?.enableDebugLogging(true)
        iabHelper?.skipPurchaseVerification = skipPurchaseVerification
        billingInitialized = false
        return true
    }

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)

        this.store = getStoreByInstaller()
        // this.store = Store.ONESTORE
        // initializeBillingHelper()
    }

    private fun getStoreByInstaller(): Store?  {
        val applicationContext = cordova.context.applicationContext
        val pm = applicationContext.packageManager
        return Store.findByInstallerPackageName(pm.getInstallerPackageName(applicationContext.packageName))
    }

    private fun makeError(message: String, resultCode: Int?, result: IabResult): JSONObject {
        return makeError(message, resultCode, result.message, result.response)
    }

    @JvmOverloads
    protected fun makeError(message: String?, resultCode: Int? = null, text: String? = null, response: Int? = null): JSONObject {
        if (message != null) {
            Log.d(TAG, "Error: $message")
        }
        val error = JSONObject()
        try {
            if (resultCode != null) {
                error.put("code", resultCode)
            }
            if (message != null) {
                error.put("message", message)
            }
            if (text != null) {
                error.put("text", text)
            }
            if (response != null) {
                error.put("response", response)
            }
        } catch (e: JSONException) {
        }

        return error
    }

    override fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        Log.d(TAG, "executing on android")
        return when (action) {
            "init" -> init(args, callbackContext)
            "buy" -> buy(args, callbackContext)
            "subscribe" -> subscribe(args, callbackContext)
            "consumePurchase" -> consumePurchase(args, callbackContext)
            "getSkuDetails" -> getSkuDetails(args, callbackContext)
            "restorePurchases" -> restorePurchases(args, callbackContext)
            "store" -> {
                callbackContext.success(this.store?.name?.toLowerCase().orEmpty())
                true
            }
            "nativeStore" -> {
                callbackContext.success(getStoreByInstaller()?.name?.toLowerCase().orEmpty())
                true
            }
            "setStore" -> {
               setStore(args, callbackContext)
            }
            else -> false
        }
    }

    fun setStore(args: JSONArray, callbackContext: CallbackContext): Boolean {
        val store = args.optString(0)?.let { Store.valueOf(it.toUpperCase()) }

        Log.i(TAG, "setting Store: ${store}")

        return when(store) {
            null -> {
                callbackContext.error("invalid store : ${args.optString(0)}")
                false
            }
            else -> {
                if (this.store != store) {
                    this.store = store
                    this.billingInitialized = false
                    this.iabHelper = null
                }
                callbackContext.success()
                true
            }
        }
    }


    protected fun init(args: JSONArray, callbackContext: CallbackContext): Boolean {
        if (billingInitialized) {
            Log.d(TAG, "Billing already initialized")
            callbackContext.success()
            return true
        }

        if (iabHelper == null && !this.initializeBillingHelper()) {
            callbackContext.error(makeError("Billing cannot be initialized", UNABLE_TO_INITIALIZE))
        } else {
            iabHelper!!.startSetup { result ->
                if (!result.isSuccess) {
                    callbackContext.error(makeError("Unable to initialize billing: " + result.toString(), UNABLE_TO_INITIALIZE, result))
                } else {
                    Log.d(TAG, "Billing initialized")
                    billingInitialized = true
                    callbackContext.success()
                }
            }
        }
        return true
    }

    private fun runPayment(args: JSONArray, callbackContext: CallbackContext, subscribe: Boolean): Boolean {
        val sku: String
        try {
            sku = args.getString(0)
        } catch (e: JSONException) {
            callbackContext.error(makeError("Invalid SKU", INVALID_ARGUMENTS))
            return false
        }

        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED))
            return false
        }
        val cordovaActivity = this.cordova.activity
        val newOrder = orderSerial.getAndIncrement()
        this.cordova.setActivityResultCallback(this)

        val oipfl:(IabResult, Purchase?) -> Unit = { result: IabResult, purchase: Purchase? ->
                if (result.isFailure) {
                    val response = result.response
                    if (response == IabHelper.IABHELPER_BAD_RESPONSE || response == IabHelper.IABHELPER_UNKNOWN_ERROR) {
                        callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result))
                    } else if (response == IabHelper.IABHELPER_VERIFICATION_FAILED) {
                        callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result))
                    } else if (response == IabHelper.IABHELPER_USER_CANCELLED) {
                        callbackContext.error(makeError("Purchase Cancelled", USER_CANCELLED, result))
                    } else if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {
                        callbackContext.error(makeError("Item already owned", ITEM_ALREADY_OWNED, result))
                    } else {
                        callbackContext.error(makeError("Error completing purchase: $response", UNKNOWN_ERROR, result))
                    }
                } else {
                    try {
                        val pluginResponse = JSONObject()
                        pluginResponse.put("orderId", purchase?.orderId)
                        pluginResponse.put("packageName", purchase?.packageName)
                        pluginResponse.put("productId", purchase?.sku)
                        pluginResponse.put("purchaseTime", purchase?.purchaseTime)
                        pluginResponse.put("purchaseState", purchase?.purchaseState)
                        pluginResponse.put("purchaseToken", purchase?.token)
                        pluginResponse.put("signature", purchase?.signature)
                        pluginResponse.put("type", purchase?.itemType)
                        pluginResponse.put("receipt", purchase?.originalJson)
                        callbackContext.success(pluginResponse)
                    } catch (e: JSONException) {
                        callbackContext.error("Purchase succeeded but success handler failed")
                    }

                }
            }
        if (subscribe) {
            iabHelper!!.launchSubscriptionPurchaseFlow(cordovaActivity, sku, newOrder, "", oipfl)
        } else {
            iabHelper!!.launchPurchaseFlow(cordovaActivity, sku, newOrder, "", oipfl)
        }
        return true
    }

    protected fun subscribe(args: JSONArray, callbackContext: CallbackContext): Boolean {
        return runPayment(args, callbackContext, true)
    }

    protected fun buy(args: JSONArray, callbackContext: CallbackContext): Boolean {
        return runPayment(args, callbackContext, false)
    }

    protected fun consumePurchase(args: JSONArray, callbackContext: CallbackContext): Boolean {
        val purchase: Purchase?
        try {
            val type = args.getString(0)
            val receipt = args.getString(1)
            val signature = args.getString(2)
            purchase = Purchase.apply(ItemType.valueOf(type), signature, receipt)
        } catch (e: JSONException) {
            callbackContext.error(makeError("Unable to parse purchase token", INVALID_ARGUMENTS))
            return false
        }

        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED))
            return false
        }
        iabHelper?.consumeAsync(purchase) { purchase1, result ->
            if (result.isFailure) {
                val response = result.response
                if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED) {
                    callbackContext.error(makeError("Error consuming purchase", ITEM_NOT_OWNED, result))
                } else {
                    callbackContext.error(makeError("Error consuming purchase", CONSUME_FAILED, result))
                }
            } else {
                try {
                    val pluginResponse = JSONObject()
                    pluginResponse.put("transactionId", purchase1.orderId)
                    pluginResponse.put("productId", purchase1.sku)
                    pluginResponse.put("token", purchase1.token)
                    callbackContext.success(pluginResponse)
                } catch (e: JSONException) {
                    callbackContext.error("Consume succeeded but success handler failed")
                }

            }
        }
        return true
    }

    protected fun getSkuDetails(args: JSONArray, callbackContext: CallbackContext): Boolean {
        val moreSkus = ArrayList<String>()
        try {
            for (i in 0 until args.length()) {
                moreSkus.add(args.getString(i))
                Log.d(TAG, "get sku:" + args.getString(i))
            }
        } catch (e: JSONException) {
            callbackContext.error(makeError("Invalid SKU", INVALID_ARGUMENTS))
            return false
        }

        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED))
            return false
        }
        iabHelper?.queryInventoryAsync(moreSkus) { result, inventory ->
            if (result.isFailure) {
                callbackContext.error("Error retrieving SKU details")
            } else {
                val response = JSONArray()
                try {
                    for (sku in moreSkus) {
                        inventory?.getSkuDetails(sku)?.also {skuDetails ->
                            val detailsJson = JSONObject().apply {
                                put("productId", skuDetails.sku)
                                put("title", skuDetails.title)
                                put("description", skuDetails.description)
                                put("priceAsDecimal", skuDetails.priceAsDecimal)
                                put("price", skuDetails.price)
                                put("type", skuDetails.type)
                                put("currency", skuDetails.priceCurrency)
                            }
                            response.put(detailsJson)
                        }
                    }

                    callbackContext.success(response)
                } catch (e: JSONException) {
                    callbackContext.error(e.message)
                }

            }
        }
        return true
    }

    protected fun restorePurchases(args: JSONArray, callbackContext: CallbackContext): Boolean {
        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED))
        } else {
            iabHelper?.queryPurchasesAsync { result, purchases ->
                if (result.isFailure) {
                    callbackContext.error("Error retrieving purchase details")
                } else {
                    val response = JSONArray()
                    try {
                        purchases?.forEach { purchase ->
                            val detailsJson = JSONObject().apply {
                                put("orderId", purchase.orderId)
                                put("packageName", purchase.packageName)
                                put("productId", purchase.sku)
                                put("purchaseTime", purchase.purchaseTime)
                                put("purchaseState", purchase.purchaseState)
                                put("purchaseToken", purchase.token)
                                put("signature", purchase.signature)
                                put("type", purchase.itemType)
                                put("receipt", purchase.originalJson)
                            }
                            response.put(detailsJson)
                        }

                        callbackContext.success(response)
                    } catch (e: JSONException) {
                        callbackContext.error(e.message)
                    }
                }
            }
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent) {
        when(iabHelper?.handleActivityResult(requestCode, resultCode, intent)) {
            false -> super.onActivityResult(requestCode, resultCode, intent)
        }
    }


    override fun onDestroy() {
        if (iabHelper != null) iabHelper!!.dispose()
        iabHelper = null
        billingInitialized = false
    }

    companion object {

        protected const val TAG = "google.payments"

        const val OK = 0
        const val INVALID_ARGUMENTS = -1
        const val UNABLE_TO_INITIALIZE = -2
        const val BILLING_NOT_INITIALIZED = -3
        const val UNKNOWN_ERROR = -4
        const val USER_CANCELLED = -5
        const val BAD_RESPONSE_FROM_SERVER = -6
        const val VERIFICATION_FAILED = -7
        const val ITEM_UNAVAILABLE = -8
        const val ITEM_ALREADY_OWNED = -9
        const val ITEM_NOT_OWNED = -10
        const val CONSUME_FAILED = -11

        const val PURCHASE_PURCHASED = 0
        const val PURCHASE_CANCELLED = 1
        const val PURCHASE_REFUNDED = 2
    }
}
