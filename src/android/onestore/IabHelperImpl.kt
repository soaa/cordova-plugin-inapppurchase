package com.alexdisler.inapppurchases.onestore

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alexdisler.inapppurchases.IabHelper
import com.alexdisler.inapppurchases.IabResult
import com.alexdisler.inapppurchases.Inventory
import com.alexdisler.inapppurchases.Purchase
import com.alexdisler.inapppurchases.enums.ItemType
import com.onestore.iap.api.IapResult
import com.onestore.iap.api.PurchaseClient



class IabHelperImpl(ctx: Context) : IabHelper(ctx) {
    private lateinit var storeKey: String

    private var mPurchaseClient: PurchaseClient? = null

    init {
        val appInfo = ctx.applicationContext.packageManager.getApplicationInfo(ctx.applicationContext.packageName, PackageManager.GET_META_DATA)
        if (appInfo != null) {
            storeKey = appInfo.metaData.getString(ONESTORE_KEY)
        }
    }
    override fun launchPurchaseFlow(act: Activity, sku: String, itemType: ItemType, requestCode: Int, extraData: String, listener: (IabResult, Purchase?) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun startSetup(listener: (IabResult) -> Unit) {
        val mServiceConnectionListener = object : PurchaseClient.ServiceConnectionListener {
            override fun onConnected() {
                logDebug("Service connected")
                if (disposed) return

                mPurchaseClient?.isBillingSupported(IAP_API_VERSION, object : PurchaseClient.BillingSupportedListener {
                    override fun onErrorNeedUpdateException() {
                        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                    }

                    override fun onSuccess() {
                        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                    }

                    override fun onErrorRemoteException() {
                        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                    }

                    override fun onError(result: IapResult?) {
                        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                    }

                    override fun onErrorSecurityException() {
                        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                    }

                })
            }

            override fun onDisconnected() {
                mPurchaseClient = null
                logDebug("Service disconnected")
            }

            override fun onErrorNeedUpdateException() {
                logError("connect onError, 원스토어 서비스앱의 업데이트가 필요합니다")
                PurchaseClient.launchUpdateOrInstallFlow(context as Activity?)
            }
        }

        mPurchaseClient = PurchaseClient(context, storeKey)
        mPurchaseClient?.connect(mServiceConnectionListener)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun disposeInternal() {
        mPurchaseClient?.terminate()
        mPurchaseClient = null
    }

    override fun consumeAsync(purchase: Purchase, listener: (Purchase, IabResult) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun consumeAsync(purchases: List<Purchase>, listener: (List<Purchase>, List<IabResult>) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun queryInventoryAsync(querySkuDetails: Boolean, moreSkus: List<String>?, listener: ((IabResult, Inventory) -> Unit)?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun queryInventory(querySkuDetails: Boolean, moreSkus: List<String>, moreSubsSkus: List<String>): Inventory {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    companion object {
        const val ONESTORE_KEY = "one_store_key"
        const val IAP_API_VERSION = 5
    }
}