package com.ace77505.dex2oat.domain

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.ace77505.dex2oat.model.PackageItem
import java.io.File

class PackageManagerRepository(private val packageManager: PackageManager) {
    fun loadPackages(): List<PackageItem> {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.asSequence()
            .filter { app -> app.packageName != "android" }
            .map { app ->
            val label = packageManager.getApplicationLabel(app).toString()
            val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(app.packageName, 0)
            }
            val icon = runCatching {
                packageManager.getApplicationIcon(app).toBitmap().asImageBitmap()
            }.getOrNull()
            val apkSize = runCatching { File(app.sourceDir).length() }.getOrDefault(0L)
            PackageItem(
                packageName = app.packageName,
                label = label,
                icon = icon,
                lastUpdateTime = packageInfo.lastUpdateTime,
                apkSize = apkSize,
                isSystem = app.isSystemApp(),
                isUpdatedSystem = app.isUpdatedSystemApp(),
                isAab = !app.splitSourceDirs.isNullOrEmpty()
            )
        }
            .toList()
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
