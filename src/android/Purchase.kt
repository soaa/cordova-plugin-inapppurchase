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
 * Represents an in-app billing purchase.
 */
class Purchase
constructor(
        val itemType: ItemType,
        val signature: String,
        val orderId: String,
        val packageName: String,
        val sku: String,
        val purchaseTime: Long = 0,
        val purchaseState: Int = 0,
        val developerPayload: String,
        val token: String?,
        val originalJson: String
) {

    override fun toString(): String {
        return "PurchaseInfo(type:$itemType):$originalJson"
    }

    companion object {

        @Throws(JSONException::class)
        fun initFromPlayStore(itemType: ItemType, signature: String, mJson: String): Purchase{
            val o = JSONObject(mJson)
            return Purchase(
                    itemType,
                    signature,
                    orderId = o.optString("orderId"),
                    packageName = o.optString("packageName"),
                    sku = o.optString("productId"),
                    purchaseTime = o.optLong("purchaseTime"),
                    purchaseState = o.optInt("purchaseState"),
                    developerPayload = o.optString("developerPayload"),
                    token = o.optString("token", o.optString("purchaseToken")),
                    originalJson = mJson
            )
        }
    }
}
