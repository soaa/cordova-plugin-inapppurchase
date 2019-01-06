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

package com.alexdisler.inapppurchases

import com.alexdisler.inapppurchases.enums.ItemType

import org.json.JSONException
import org.json.JSONObject

/**
 * Represents an in-app product's listing details.
 */
class SkuDetails @Throws(JSONException::class)
constructor(
        val ItemType: ItemType,
        val sku: String,
        val type: String,
        val priceAsDecimal: Double? = null,
        val price: String,
        val priceCurrency: String,
        val title: String,
        val description: String,
        val mJson: String? = null
        ) {

    override fun toString(): String {
        return "SkuDetails:$mJson"
    }

    companion object {
        fun initFromPlayStore(itemType: ItemType, mJson: String):SkuDetails {
            val o = JSONObject(mJson)

            return SkuDetails(
                    itemType,
                    sku = o.optString("productId"),
                    type = o.optString("type"),
                    price = o.optString("price"),
                    priceCurrency = o.optString("price_currency_code"),
                    priceAsDecimal = java.lang.Double.parseDouble(o.optString("price_amount_micros")) / java.lang.Double.valueOf(1000000.0),
                    title = o.optString("title"),
                    description = o.optString("description"),
                    mJson = mJson
            )
        }
    }
}
