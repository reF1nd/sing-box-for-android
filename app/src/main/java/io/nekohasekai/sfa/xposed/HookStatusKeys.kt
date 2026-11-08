package io.nekohasekai.sfa.xposed

object HookStatusKeys {
    const val DESCRIPTOR = "android.net.IConnectivityManager"

    // Upstream SFA uses 0x5F00-0x5F04 on the same binder and only trusts its own package,
    // so this build uses a separate range to keep the two modules from answering each other.
    const val TRANSACTION_STATUS = 0x524600
    const val TRANSACTION_UPDATE_PRIVILEGE_SETTINGS = 0x524601
    const val TRANSACTION_GET_ERRORS = 0x524602
    const val TRANSACTION_EXPORT_DEBUG_INFO = 0x524603
    const val TRANSACTION_GET_INSTALLED_PACKAGES = 0x524604

    // Status code still answered by modules older than HookModuleVersion 4.
    const val LEGACY_TRANSACTION_STATUS = 0x5F00
}
