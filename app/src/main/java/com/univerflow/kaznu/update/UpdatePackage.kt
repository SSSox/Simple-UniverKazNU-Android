package com.univerflow.kaznu.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/** What a downloaded APK must be before it reaches the system installer: this app, newer, signed by the same certificate. */
object UpdatePackage {
    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun sameApp(context: Context, apk: File): Boolean {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else signaturesFlag()
        val offered = manager.getPackageArchiveInfo(apk.path, flags) ?: return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        return offered.packageName == context.packageName && code(offered) > code(installed) &&
            signers(offered).isNotEmpty() && signers(offered) == signers(installed)
    }

    @Suppress("DEPRECATION") private fun signaturesFlag() = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION") private fun code(info: PackageInfo) =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION") private fun signers(info: PackageInfo): Set<Signature> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet().orEmpty()
}
