package com.ace77505.dex2oat.domain

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.ace77505.dex2oat.model.PackageItem

class PackageManagerRepository(private val packageManager: PackageManager) {
    fun loadPackages(): List<PackageItem> {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.map { app ->
            val label = packageManager.getApplicationLabel(app).toString()
            PackageItem(
                packageName = app.packageName,
                label = label,
                isSystem = app.isSystemApp(),
                isUpdatedSystem = app.isUpdatedSystemApp(),
                isAab = !app.splitSourceDirs.isNullOrEmpty()
            )
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }

    fun getLaunchComponent(packageName: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return intent?.component?.flattenToShortString()
    }

    private fun ApplicationInfo.isSystemApp(): Boolean {
        return flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private fun ApplicationInfo.isUpdatedSystemApp(): Boolean {
        return flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    }
}
