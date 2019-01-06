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
import com.alexdisler.inapppurchases.playstore.IabHelperImpl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.alexdisler.inapppurchases.enums.ItemType

open class InAppBillingV3 : CordovaPlugin() {

    private var iabHelper: IabHelper? = null

    private var store: Store? = null

    private var billingInitialized = false
    private var orderSerial = AtomicInteger(0)

    private var manifestObject: JSONObject? = null

    private val manifestContents: JSONObject?
        get() {
            if (manifestObject != null) return manifestObject

            val context = this.cordova.activity
            val `is`: InputStream
            try {
                `is` = context.assets.open("www/manifest.json")
                val s = Scanner(`is`).useDelimiter("\\A")
                val manifestString = if (s.hasNext()) s.next() else ""
                Log.d(TAG, "manifest:$manifestString")
                manifestObject = JSONObject(manifestString)
            } catch (e: IOException) {
                Log.d(TAG, "Unable to read manifest file:" + e.toString())
                manifestObject = null
            } catch (e: JSONException) {
                Log.d(TAG, "Unable to parse manifest file:" + e.toString())
                manifestObject = null
            }

            return manifestObject
        }

    private val base64EncodedPublicKey: String?
        get() {
            val manifestObject = manifestContents
            return manifestObject?.optString("play_store_key")
        }

    private fun shouldSkipPurchaseVerification(): Boolean {
        val manifestObject = manifestContents
        return manifestObject?.optBoolean("skip_purchase_verification") ?: false
    }

    private fun initializeBillingHelper(): Boolean {
        if (iabHelper != null) {
            Log.d(TAG, "Billing already initialized")
            return true
        }


        val context = this.cordova.activity
        val base64EncodedPublicKey = base64EncodedPublicKey
        val skipPurchaseVerification = shouldSkipPurchaseVerification()
        if (base64EncodedPublicKey != null) {
            iabHelper = IabHelperImpl(context, base64EncodedPublicKey)
            iabHelper!!.skipPurchaseVerification = skipPurchaseVerification
            billingInitialized = false
            return true
        }

        Log.d(TAG, "Unable to initialize billing")
        return false
    }

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)

        val applicationContext = cordova.context.applicationContext
        val pm = applicationContext.packageManager
        this.store = Store.findByInstallerPackageName(pm.getInstallerPackageName(applicationContext.packageName))
        initializeBillingHelper()
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
                callbackContext.success(this.store!!.name.toLowerCase())
                true
            }
            else -> false
        }
    }


    protected fun init(args: JSONArray, callbackContext: CallbackContext): Boolean {
        if (billingInitialized) {
            Log.d(TAG, "Billing already initialized")
            callbackContext.success()
            return true
        }

        if (iabHelper == null) {
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
            purchase = Purchase.initFromPlayStore(ItemType.valueOf(type), signature, receipt)
        } catch (e: JSONException) {
            callbackContext.error(makeError("Unable to parse purchase token", INVALID_ARGUMENTS))
            return false
        }

        if (purchase == null) {
            callbackContext.error(makeError("Unrecognized purchase token", INVALID_ARGUMENTS))
            return false
        }
        if (iabHelper == null || !billingInitialized) {
            callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED))
            return false
        }
        iabHelper!!.consumeAsync(purchase) { purchase1, result ->
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
        iabHelper!!.queryInventoryAsync(true, moreSkus) { result, inventory ->
            if (result.isFailure) {
                callbackContext.error("Error retrieving SKU details")
            } else {
                val response = JSONArray()
                try {
                    for (sku in moreSkus) {
                        val skuDetails = inventory.getSkuDetails(sku)
                        if (skuDetails != null) {
                            val detailsJson = JSONObject()
                            detailsJson.put("productId", skuDetails!!.sku)
                            detailsJson.put("title", skuDetails!!.title)
                            detailsJson.put("description", skuDetails!!.description)
                            detailsJson.put("priceAsDecimal", skuDetails!!.priceAsDecimal)
                            detailsJson.put("price", skuDetails!!.price)
                            detailsJson.put("type", skuDetails!!.type)
                            detailsJson.put("currency", skuDetails!!.priceCurrency)
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
            iabHelper!!.queryInventoryAsync { result, inventory ->
                if (result.isFailure) {
                    callbackContext.error("Error retrieving purchase details")
                } else {
                    val response = JSONArray()
                    try {
                        inventory.allPurchases.forEach { purchase ->
                            val detailsJson = JSONObject()
                            detailsJson.put("orderId", purchase.orderId)
                            detailsJson.put("packageName", purchase.packageName)
                            detailsJson.put("productId", purchase.sku)
                            detailsJson.put("purchaseTime", purchase.purchaseTime)
                            detailsJson.put("purchaseState", purchase.purchaseState)
                            detailsJson.put("purchaseToken", purchase.token)
                            detailsJson.put("signature", purchase.signature)
                            detailsJson.put("type", purchase.itemType)
                            detailsJson.put("receipt", purchase.originalJson)
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
        if (!iabHelper!!.handleActivityResult(requestCode, resultCode, intent)) {
            super.onActivityResult(requestCode, resultCode, intent)
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
