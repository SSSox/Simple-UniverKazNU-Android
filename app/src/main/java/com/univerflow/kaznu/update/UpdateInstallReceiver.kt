package com.univerflow.kaznu.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/** The system installer's answer to the session the app committed; only the system can reach this unexported receiver. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        UpdateManager.get(context).installResult(status, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
    }
}
