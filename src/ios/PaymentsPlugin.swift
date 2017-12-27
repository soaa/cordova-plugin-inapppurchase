import SwiftyStoreKit

@objc(PaymentsPlugin) class PaymentsPlugin: CDVPlugin {
  override func pluginInitialize() {

  }

    @objc(getProducts:) func getProducts(command: CDVInvokedUrlCommand) {
        if let productIds = command.arguments.first as? NSArray {
            let products = Set.init(productIds as! [String])
    
            SwiftyStoreKit.retrieveProductsInfo(products) { result in
                if result.error != nil {
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: String(describing: result.error))
                    self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
                    return
                } else {
                    
                    let validProducts = result.retrievedProducts.map { product -> [String: Any] in
                        let formatter = NumberFormatter()
                        formatter.numberStyle = .currency
                        formatter.locale = product.priceLocale

                        return [
                            "productId" : product.productIdentifier,
                            "title" : product.localizedTitle,
                            "description" : product.localizedDescription,
                            "priceAsDecimal" : product.price,
                            "price" : product.localizedPrice,
                            "currency" : formatter.currencyCode
                            ] as [String : Any]
                    }
                    
                    let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_OK,
                                                            messageAs: ["products": validProducts, "invalidProductsIds": result.invalidProductIDs])
                    
                    pluginResult?.setKeepCallbackAs(true)
                    
                }
            }
        } else {
            let pluginResult = CDVPluginResult.init(status: CDVCommandStatus_ERROR, messageAs: "ProductIds must be an array")
            self.commandDelegate.send(pluginResult, callbackId: command.callbackId)
        }
    }
    
    @objc(buy:) func buy(command: CDVInvokedUrlCommand) {
        
    }
    
    @objc(restorePurchases:) func restorePurchases(command: CDVInvokedUrlCommand) {
        
    }
    
    @objc(getReceipt:) func getReceipt(command: CDVInvokedUrlCommand) {
        
    }
}

//MARK: extension PaymentsPlugin
extension AppDelegate {

}
