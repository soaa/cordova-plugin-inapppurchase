package com.alexdisler.inapppurchases.enums

enum class Store {
    GOOGLE, ONESTORE;


    companion object {

        fun findByInstallerPackageName(pn: String?): Store {
            when (pn.orEmpty()) {
                "com.skt.skaf.A000Z00040",
                "com.skt.skaf.Z0000TSEED",
                "com.kt.om.ktpackageinstaller",
                "com.android.ktpackageinstaller",
                "com.lguplus.installer"
                    -> return ONESTORE
                else
                    -> return GOOGLE
            }
        }
    }
}
