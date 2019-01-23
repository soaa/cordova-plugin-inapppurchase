package com.alexdisler.inapppurchases.onestore

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alexdisler.inapppurchases.*
import com.alexdisler.inapppurchases.Security
import com.alexdisler.inapppurchases.enums.ItemType
import com.onestore.iap.api.*
import org.apache.cordova.CordovaInterface
import android.R.attr.data
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.text.NumberFormat
import java.util.*


class IabHelperImpl(private val cordova: CordovaInterface) : IabHelper(cordova.activity) {
    private lateinit var storeKey: String

    private var mPurchaseClient: PurchaseClient? = null
    private var mRequestCode: Int? = null

    init {
        val appInfo = context.applicationContext.packageManager.getApplicationInfo(context.applicationContext.packageName, PackageManager.GET_META_DATA)
        if (appInfo != null) {
            storeKey = appInfo.metaData.getString(ONESTORE_KEY)
        }
    }

    private fun toPurchase(itemType: ItemType, it: PurchaseData): Purchase {
        return Purchase(
                itemType,
                it.signature,
                it.orderId,
                it.packageName,
                it.productId,
                it.purchaseTime,
                it.purchaseState,
                it.developerPayload,
                it.purchaseId,
                it.purchaseData
        )
    }
    override fun launchPurchaseFlow(act: Activity, sku: String, itemType: ItemType, requestCode: Int, extraData: String, listener: (IabResult, Purchase?) -> Unit) {
        checkNotDisposed()
        checkSetupDone("purchase")
        this.mRequestCode = requestCode

        mPurchaseClient?.launchPurchaseFlowAsync(
                IAP_API_VERSION,
                act,
                requestCode,
                sku,
                "",
                when (itemType) {
                    ItemType.INAPP -> IapEnum.ProductType.IN_APP.type
                    else -> IapEnum.ProductType.AUTO.type
                },
                extraData,
                "",
                false,
                object : PurchaseClient.PurchaseFlowListener {
                    override fun onErrorNeedUpdateException() {
                        listener(handleNeedUpdateException(), null)
                    }

                    override fun onSuccess(pd: PurchaseData?) {
                        pd?.let {
                            listener(
                                    IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK),
                                    toPurchase(itemType, it)
                            )
                        }
                    }

                    override fun onErrorRemoteException() {
                        listener(IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION), null)
                    }

                    override fun onError(result: IapResult?) {
                        listener(handleError(result), null)
                    }

                    override fun onErrorSecurityException() {
                        listener(IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED), null)
                    }

                }
        )
    }

    private fun handleNeedUpdateException(): IabResult {
        logError("connect onError, 원스토어 서비스앱의 업데이트가 필요합니다")
        PurchaseClient.launchUpdateOrInstallFlow(cordova.activity)
        return IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION, "원스토어 서비스앱의 업데이트가 필요합니다")
    }

    private fun handleNeedLogin():Unit {
        val alertDialog = AlertDialog.Builder(cordova.activity)
        alertDialog.setMessage("원스토어 로그인 후 구매가 가능합니다.\n로그인하시겠습니까?")
        alertDialog.setPositiveButton("확인") { dialog, res ->
            mPurchaseClient?.launchLoginFlowAsync(IAP_API_VERSION, cordova.activity, LOGIN_REQUEST_CODE, object : PurchaseClient.LoginFlowListener {
                override fun onErrorNeedUpdateException() {
                    handleNeedUpdateException()
                }

                override fun onSuccess() {
                }

                override fun onErrorRemoteException() {
                }

                override fun onError(p0: IapResult?) {
                }

                override fun onErrorSecurityException() {
                }
            })
        }

        alertDialog.setNegativeButton("취소") { dialog, res->
            logDebug("원스토어 로그인 취소")
        }

        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun handleError(result: IapResult?):IabResult {
        when(result?.code) {
            IapResult.RESULT_NEED_LOGIN.code -> handleNeedLogin()
        }

        return IabResult(result?.code
                ?: IabHelper.IABHELPER_UNKNOWN_ERROR, result?.description)
    }

    override fun startSetup(listener: (IabResult) -> Unit) {
        val mServiceConnectionListener = object : PurchaseClient.ServiceConnectionListener {
            override fun onConnected() {
                logDebug("Service connected")
                if (disposed) return

                mPurchaseClient?.isBillingSupportedAsync(IAP_API_VERSION, object : PurchaseClient.BillingSupportedListener {
                    override fun onErrorNeedUpdateException() {
                        listener(
                                handleNeedUpdateException()
                        )
                    }

                    override fun onSuccess() {
                        setupDone = true
                        listener(IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK))
                    }

                    override fun onErrorRemoteException() {
                        listener(IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION))
                    }

                    override fun onError(result: IapResult?) {
                        listener(handleError(result))
                    }

                    override fun onErrorSecurityException() {
                        listener(IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED))
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
                listener(
                        IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION, "원스토어 서비스앱의 업데이트가 필요합니다")
                )

            }
        }

        mPurchaseClient = PurchaseClient(context, storeKey)
        mPurchaseClient?.connect(mServiceConnectionListener)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            mRequestCode ->
                /*
             * launchPurchaseFlowAsync API 호출 시 전달받은 intent 데이터를 handlePurchaseData를 통하여 응답값을 파싱합니다.
             * 파싱 이후 응답 결과를 launchPurchaseFlowAsync 호출 시 넘겨준 PurchaseFlowListener 를 통하여 전달합니다.
             */
                when (resultCode) {
                    Activity.RESULT_OK ->
                        return when (mPurchaseClient?.handlePurchaseData(data)) {
                            true -> true
                            else -> {
                                logError("onActivityResult handlePurchaseData false ")
                                false
                            }
                        }
                    else -> {
                        logError("onActivityResult user canceled")
                        return false
                        // user canceled , do nothing..
                    }
                }
            LOGIN_REQUEST_CODE ->
                when (resultCode) {
                    Activity.RESULT_OK ->
                        return when (mPurchaseClient?.handleLoginData(data)) {
                            true -> true
                            else -> {
                                logError("onActivityResult handlePurchaseData false ")
                                false
                            }
                        }
                    else -> {
                        logError("onActivityResult user canceled")
                        return false
                        // user canceled , do nothing..
                    }
                }
            else -> return false
        }
    }

    override fun disposeInternal() {
        mPurchaseClient?.terminate()
        mPurchaseClient = null
    }

    override fun consumeAsync(purchase: Purchase, listener: (Purchase, IabResult) -> Unit) {
        checkNotDisposed()
        checkSetupDone("consume")

        val purchaseObject = JSONObject(purchase.originalJson)
        val purchaseData =  PurchaseData.builder()
                .orderId(purchaseObject.optString("orderId"))
                .packageName(purchaseObject.optString("packageName"))
                .productId(purchaseObject.optString("productId"))
                .purchaseTime(purchaseObject.optLong("purchaseTime"))
                .purchaseId(purchaseObject.optString("purchaseId"))
                .developerPayload(purchaseObject.optString("developerPayload"))
                .signature(purchase.signature)
                .purchaseData(purchase.originalJson).build()


        mPurchaseClient?.consumeAsync(IAP_API_VERSION, purchaseData, object: PurchaseClient.ConsumeListener {
            override fun onErrorNeedUpdateException() {
                listener(purchase, handleNeedUpdateException())
            }

            override fun onSuccess(pd: PurchaseData?) {
                pd?.let {
                    listener(
                            toPurchase(purchase.itemType, it),
                            IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK)
                    )
                }
            }

            override fun onErrorRemoteException() {
                listener(purchase, IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION))
            }

            override fun onError(result: IapResult?) {
                listener(purchase, handleError(result))
            }

            override fun onErrorSecurityException() {
                listener(purchase, IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED))
            }

        })
    }

    /*
    override fun consumeAsync(purchases: List<Purchase>, listener: (List<Purchase>, List<IabResult>) -> Unit) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    */

    override fun queryInventoryAsync(moreSkus: List<String>, listener: (IabResult, Inventory?) -> Unit) {
        checkNotDisposed()
        checkSetupDone("queryInventory")
        val inv = Inventory()
        mPurchaseClient?.queryProductsAsync(IAP_API_VERSION, moreSkus as ArrayList<String>?, ItemType.INAPP.value, object : PurchaseClient.QueryProductsListener {
            override fun onErrorNeedUpdateException() {
                handleNeedUpdateException()
            }

            override fun onSuccess(details: MutableList<ProductDetail>?) {
                details?.forEach {
                    val priceAsDecimal = Integer.parseInt(it.price).toDouble()

                    when (it.type) {
                        IapEnum.ProductType.IN_APP.type -> {
                            inv.addSkuDetails(
                                    SkuDetails(
                                            ItemType.INAPP,
                                            it.productId,
                                            it.type,
                                            price = NumberFormat.getCurrencyInstance(Locale.KOREA).format(priceAsDecimal),
                                            priceCurrency = "KRW",
                                            priceAsDecimal = priceAsDecimal,
                                            title = it.title
                                    )
                            )
                        }
                        IapEnum.ProductType.AUTO.type -> {
                            inv.addSkuDetails(
                                    SkuDetails(
                                            ItemType.SUBSCRIPTION,
                                            it.productId,
                                            it.type,
                                            price = NumberFormat.getCurrencyInstance(Locale.KOREA).format(priceAsDecimal),
                                            priceCurrency = "KRW",
                                            priceAsDecimal = priceAsDecimal,
                                            title = it.title
                                    )
                            )
                        }
                        else -> {
                            // do nothing
                        }
                    }
                }

                listener(IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK), inv)
            }

            override fun onErrorRemoteException() {
                listener(IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION), null)
            }

            override fun onError(result: IapResult?) {
                listener(handleError(result), null)
            }

            override fun onErrorSecurityException() {
                listener(IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED), null)
            }

        })
    }


    override fun queryPurchasesAsync(listener: (IabResult, List<Purchase>?) -> Unit) {
        checkNotDisposed()
        checkSetupDone("queryPurchase")

        mPurchaseClient?.queryPurchasesAsync(IAP_API_VERSION, IapEnum.ProductType.IN_APP.type, object: PurchaseClient.QueryPurchaseListener {
            override fun onErrorNeedUpdateException() {
                listener(handleNeedUpdateException(), null)
            }

            override fun onSuccess(purchaseDatas: MutableList<PurchaseData>?, productType: String?) {
                listener(
                        IabResult(IabHelper.BILLING_RESPONSE_RESULT_OK),
                        purchaseDatas?.map {
                            toPurchase(ItemType.INAPP, it)
                        }
                )
            }

            override fun onErrorRemoteException() {
                listener(IabResult(IabHelper.IABHELPER_REMOTE_EXCEPTION), null)
            }

            override fun onError(result: IapResult?) {
                listener(handleError(result), null)
            }

            override fun onErrorSecurityException() {
                listener(IabResult(IabHelper.IABHELPER_VERIFICATION_FAILED), null)
            }

        })
    }

    /*
    @Throws(IabException::class)
    override fun queryInventory(querySkuDetails: Boolean, moreSkus: List<String>, moreSubsSkus: List<String>): Inventory {
        checkNotDisposed()
        checkSetupDone("queryInventory")
        mPurchaseClient.queryProductsAsync(IAP_API_VERSION, moreSkus as ArrayList<String>?, )

    }
    */

    companion object {
        const val ONESTORE_KEY = "one_store_key"
        const val IAP_API_VERSION = 5

        const val LOGIN_REQUEST_CODE = 11111111
    }
}