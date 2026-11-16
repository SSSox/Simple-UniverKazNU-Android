package com.univerflow.kaznu.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/** What a downloaded APK must be before it reaches the system installer: this app, newer, signed by the same certificate or its rotated successor. */
object UpdatePackage {
    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun sameApp(context: Context, apk: File): Boolean {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else signaturesFlag()
        // Android 9 and 10 read an archive's certificates only for GET_SIGNATURES; signingInfo is then filled as well.
        val archiveFlags = if (Build.VERSION.SDK_INT in 28..29) flags or signaturesFlag() else flags
        val offered = manager.getPackageArchiveInfo(apk.path, archiveFlags) ?: return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        return offered.packageName == context.packageName && code(offered) > code(installed) &&
            sameSigners(signers(offered), history(offered), signers(installed))
    }

    /**
     * Whether an APK signed by [offered] may replace one signed by [installed]. A key changed with `apksigner rotate`
     * (v3, Android 9+) keeps the installed key in [offeredHistory], and Android accepts the update through that lineage.
     * Several signers have no history and must match exactly.
     */
    internal fun <T> sameSigners(offered: Set<T>, offeredHistory: List<T>?, installed: Set<T>): Boolean = when {
        offered.isEmpty() || installed.isEmpty() -> false
        offered == installed -> true
        offered.size > 1 || installed.size > 1 -> false
        else -> offeredHistory.orEmpty().contains(installed.single())
    }

    @Suppress("DEPRECATION") private fun signaturesFlag() = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION") private fun code(info: PackageInfo) =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION") private fun signers(info: PackageInfo): Set<Signature> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()

    /** The rotation chain up to the current signer (Android 9+); null with several signers or before Android 9. */
    private fun history(info: PackageInfo): List<Signature>? =
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.signingCertificateHistory?.toList() else null
}
