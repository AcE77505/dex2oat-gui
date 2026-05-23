package com.ace77505.dex2oat.model

import androidx.compose.ui.graphics.ImageBitmap

data class PackageItem(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val lastUpdateTime: Long,
    val apkSize: Long,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isAab: Boolean
)
