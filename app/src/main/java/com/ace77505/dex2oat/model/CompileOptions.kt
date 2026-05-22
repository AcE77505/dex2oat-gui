package com.ace77505.dex2oat.model

data class CompileOptions(
    val compileFilter: String = "speed",
    val extraDex2OatOptions: String = "",
    val cpuCount: Int? = null,
    val cpuAffinity: String? = null,
    val doCleanProfile: Boolean = false,
    val doProfile: Boolean = false,
    val doCompile: Boolean = false,
    val doDumpOnly: Boolean = false,
    val doExtCompile: Boolean = false,
    val autoProfile: Boolean = false,
    val autoStart: Boolean = false,
    val forceCompile: Boolean = false,
    val extCompileFilter: String = "",
    val extPackageName: String = "",
    val extClearProfile: Boolean = false
)
