package com.ace77505.dex2oat.model

data class PackageItem(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val isAab: Boolean
)
