import SwiftyStoreKit
import UIKit

class PaymentsManager {
    static let sharedInstance = PaymentsManager()

    var pendingTransactions: [String: PaymentTransaction] = [:]
    
    var uncompletedPurchases: [Purchase] = []
    
    var callbackId: String?
    
    let PLUGIN = "PaymentsPlugin"
    
    var dateFormatter: DateFormatter
    
    private init() {
        self.dateFormatter = DateFormatter.init()
        self.dateFormatter.locale = Locale.init(identifier: "en_US_POSIX")
        self.dateFormatter.timeZone = TimeZone.init(secondsFromGMT: 0)
        self.dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    }
}

@objc(PaymentsPlugin) class PaymentsPlugin: CDVPlugin {

    override func pluginInitialize() {
        NSLog("initializing payment plugin")
        NotificationCenter.default.addObserver(self, selector: #selector(PaymentsPlugin.applicationDidFinishLaunching), name: Notification.Name.UIApplicationDidFinishLaunching, object: nil)
    }
    
    @objc func applicationDidFinishLaunching(_ notification: Notification) {
        // atomic set true to javascript manually can handle transactions
        // should end transaction with finishTransaction
        SwiftyStoreKit.completeTransactions(atomically: false) { purchases in
            for purchase in purchases {
                NSLog("payment:completeTransactions:\(purchase.productId) -> \(purchase.transaction.transactionIdentifier!)")

                switch purchase.transaction.transactionState {
                case .purchased, .restored:
                    if (PaymentsManager.sharedInstance.callbackId != nil) {
                        self.completeTransaction(purchase: purchase, callbackId: PaymentsManager.sharedInstance.callbackId!)
                    } else {
                        PaymentsManager.sharedInstance.uncompletedPurchases.append(purchase)
                    }
                case .failed, .purchasing, .deferred:
                    break // do nothing
                }
            }
        }
    }

    @objc(getProducts:) func getProducts(command: CDVInvokedUrlCommand) {
        NSLog("payment: getProducts called")
        if let productIds = command.arguments.first as? NSArray {
            let products = Set.init(productIds as! [String])
    
            SwiftyStoreKit.retrieveProductsInfo(products) { result in
                if result.error != nil {
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: String(describing: result.error))
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                    return
                } else {
                    
                    let validProducts = result.retrievedProducts.map { product -> Dictionary<String, Any> in
                        let formatter = NumberFormatter()
                        formatter.numberStyle = .currency
                        formatter.locale = product.priceLocale

                        return [
                            "productId" : product.productIdentifier,
                            "title" : product.localizedTitle,
                            "description" : product.localizedDescription,
                            "priceAsDecimal" : product.price,
                            "price" : product.localizedPrice ?? product.price.stringValue,
                            "currency" : formatter.currencyCode
                            ]
                    }
                    
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_OK,
                                                            messageAs: ["products": validProducts, "invalidProductIds": Array(result.invalidProductIDs)])!
                    
                    pluginResult.setKeepCallbackAs(true)
                    pluginResult.argumentsAsJSON()
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                }
            }
        } else {
            let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: "ProductIds must be an array")
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }
    
    @objc(buy:) func buy(command: CDVInvokedUrlCommand) {
        if let productId = command.arguments.first as? String {
            SwiftyStoreKit.purchaseProduct(productId, atomically: false) { result in
                switch result {
                case .success(let purchase):
                    let receiptData = SwiftyStoreKit.localReceiptData
                    let encReceipt = receiptData?.base64EncodedString(options: [])
                    
                    if (purchase.needsFinishTransaction && purchase.transaction.transactionIdentifier != nil) {
                        PaymentsManager.sharedInstance.pendingTransactions[purchase.transaction.transactionIdentifier!] = purchase.transaction
                    }
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_OK, messageAs: [
                        "transactionId" : purchase.transaction.transactionIdentifier ?? "",
                        "receipt" : encReceipt,
                        "signature": purchase.transaction.transactionIdentifier!,
                        "needsFinishTransaction": purchase.needsFinishTransaction
                    ])
                    
                    NSLog("payment:buy:success \(purchase)")
                    pluginResult?.setKeepCallbackAs(true)
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                case .error(let error):
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: [
                        "errorCode" : error.code.rawValue,
                        "errorMessage" : error.localizedDescription
                        ])
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                }
            }
        } else {
            let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: "ProductId must be a string.")
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }
    
    @objc(completeTransactions:) func completeTransactions(command: CDVInvokedUrlCommand) {
        while PaymentsManager.sharedInstance.uncompletedPurchases.count > 0 {
            let purchase = PaymentsManager.sharedInstance.uncompletedPurchases.remove(at: 0)
            self.completeTransaction(purchase: purchase, callbackId: command.callbackId)
        }
        
        PaymentsManager.sharedInstance.callbackId = command.callbackId
    }
    
    func completeTransaction(purchase: Purchase, callbackId: String) {
        if (purchase.needsFinishTransaction) {
            PaymentsManager.sharedInstance.pendingTransactions[purchase.transaction.transactionIdentifier!] = purchase.transaction
        }
        
        let result = CDVPluginResult.init(status: CDVCommandStatus_OK, messageAs: [
            "productId": purchase.productId,
            "date": PaymentsManager.sharedInstance.dateFormatter.string(from: purchase.transaction.transactionDate!),
            "transactionId": purchase.transaction.transactionIdentifier!,
            "transactionState": purchase.transaction.transactionState.rawValue,
            "signature": purchase.transaction.transactionIdentifier,
            "needsFinishTransaction": purchase.needsFinishTransaction,
            ])
        result?.setKeepCallbackAs(true)
        self.commandDelegate.send(result, callbackId: callbackId)
    }

    @objc(finishTransaction:) func finishTransaction(command: CDVInvokedUrlCommand) {
        if let transactionId = command.arguments.first as? String {
            if let transaction = PaymentsManager.sharedInstance.pendingTransactions[transactionId] {
                SwiftyStoreKit.finishTransaction(transaction)
                
                PaymentsManager.sharedInstance.pendingTransactions.removeValue(forKey: transactionId)
            }
        }
        
        self.commandDelegate.send(CDVPluginResult.init(status: CDVCommandStatus_OK), callbackId: command.callbackId)
    }
    
    @objc(restorePurchases:) func restorePurchases(command: CDVInvokedUrlCommand) {

        SwiftyStoreKit.restorePurchases(atomically: false) { results in
            let validTransactions = results.restoredPurchases.map { p -> [String:Any?] in
                if (p.needsFinishTransaction) {
                    PaymentsManager.sharedInstance.pendingTransactions[p.transaction.transactionIdentifier!] = p.transaction
                }
                
                return [
                    "productId": p.productId,
                    "date": PaymentsManager.sharedInstance.dateFormatter.string(from: p.transaction.transactionDate!),
                    "transactionId": p.transaction.transactionIdentifier,
                    "transactionState": p.transaction.transactionState.rawValue,
                    "signature": p.transaction.transactionIdentifier,
                    "needsFinishTransaction": p.needsFinishTransaction
                ]
            }
            
            let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_OK, messageAs: ["transactions": validTransactions])
            
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }
    
    @objc(getReceipt:) func getReceipt(command: CDVInvokedUrlCommand) {
        SwiftyStoreKit.fetchReceipt(forceRefresh: true) { result in
            switch result {
            case .success(let receiptData):
                let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_OK, messageAs: ["receipt": receiptData.base64EncodedString(options: [])])
                self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            case .error(let error):
                let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: [
                    "errorMessage" : error.localizedDescription
                    ])
                self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
            }
        }
    }
}

