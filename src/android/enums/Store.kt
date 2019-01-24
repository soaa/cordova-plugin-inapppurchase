package com.alexdisler.inapppurchases.enums

enum class Store {
    GOOGLE, ONESTORE, UNKNOWN;


    companion object {

        fun findByInstallerPackageName(pn: String?): Store? {
            return when (pn.orEmpty()) {
                "com.skt.skaf.A000Z00040",
                "com.skt.skaf.Z0000TSEED",
                "com.kt.om.ktpackageinstaller",
                "com.android.ktpackageinstaller",
                "com.kt.olleh.storefront",
                "com.kt.olleh.istore",
                "android.lgt.appstore",
                "com.lguplus.appstore",
                "com.lguplus.installer"
                    -> ONESTORE
                "com.android.vending"
                    -> GOOGLE
                else
                    -> null
            }
        }
    }
}
