package com.ace77505.dex2oat.domain

import android.content.Context
import com.ace77505.dex2oat.data.OutputManager
import com.ace77505.dex2oat.model.CompileOptions
import com.ace77505.dex2oat.model.LogEntry
import com.ace77505.dex2oat.model.LogType
import com.ace77505.dex2oat.model.OutputLocation
import com.ace77505.dex2oat.root.CommandResult
import com.ace77505.dex2oat.root.RootCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class Dex2OatExecutor(
    private val context: Context,
    private val commandRunner: RootCommandRunner,
    private val outputManager: OutputManager,
    private val packageRepository: PackageManagerRepository
) {
    data class ExecutionResult(val success: Boolean, val message: String)

    suspend fun execute(
        packageName: String,
        options: CompileOptions,
        outputLocation: OutputLocation,
        logger: (LogEntry) -> Unit
    ): ExecutionResult = withContext(Dispatchers.IO) {
        if (!commandRunner.isRootAvailable()) {
            return@withContext ExecutionResult(false, "Root 不可用")
        }
        if (packageName.isBlank()) {
            return@withContext ExecutionResult(false, "包名为空")
        }
        val logFile = outputManager.createLogFile()
        val logBuffer = StringBuilder()
        val log: (LogType, String) -> Unit = { type, message ->
            logger(LogEntry(message, type, Instant.now()))
            logBuffer.appendLine(message)
        }
        log(LogType.Info, "开始执行: $packageName")
        val packageLabel = packageRepository.loadPackages()
            .firstOrNull { it.packageName == packageName }
            ?.label
        if (!packageLabel.isNullOrBlank()) {
            log(LogType.Info, "软件名：$packageLabel")
        }

        val packagePaths = resolvePackagePaths(packageName, log) ?: run {
            writeLog(logFile, logBuffer, outputLocation)
            return@withContext ExecutionResult(false, "包路径解析失败")
        }
        if (!checkMtManager(packageName, log)) {
            writeLog(logFile, logBuffer, outputLocation)
            return@withContext ExecutionResult(false, "不支持在 MT 管理器编译自身")
        }

        if (options.doCleanProfile) {
            if (!cleanProfile(packageName, options.compileFilter, log)) {
                writeLog(logFile, logBuffer, outputLocation)
                return@withContext ExecutionResult(false, "首次 profile 编译失败")
            }
            if (options.doProfile && options.autoProfile) {
                if (!waitForProfile(packageName, options.autoStart, log)) {
                    writeLog(logFile, logBuffer, outputLocation)
                    return@withContext ExecutionResult(false, "自动生成热点失败")
                }
            }
        }
        if (options.doProfile) {
            if (!profileCompile(packageName, options.compileFilter, log)) {
                writeLog(logFile, logBuffer, outputLocation)
                return@withContext ExecutionResult(false, "profile 编译失败")
            }
        }
        if (options.doCompile) {
            val compileResult = fullCompile(
                packageName,
                packagePaths,
                options,
                log
            )
            if (!compileResult.success) {
                writeLog(logFile, logBuffer, outputLocation)
                return@withContext compileResult
            }
        }
        if (options.doDumpOnly) {
            val dumpFile = outputManager.createDumpFile()
            if (!dumpOat(packagePaths.odex, dumpFile.absolutePath, log)) {
                writeLog(logFile, logBuffer, outputLocation)
                return@withContext ExecutionResult(false, "dump 失败")
            }
            outputManager.copyToLocation(dumpFile, outputLocation, dumpFile.name)
            log(LogType.Info, "dump 保存至 ${dumpFile.absolutePath}")
        }
        if (options.doExtCompile) {
            val extResult = extCompile(options, log)
            if (!extResult.success) {
                writeLog(logFile, logBuffer, outputLocation)
                return@withContext extResult
            }
        }
        writeLog(logFile, logBuffer, outputLocation)
        ExecutionResult(true, "执行完成")
    }

    private fun writeLog(
        logFile: File,
        logBuffer: StringBuilder,
        outputLocation: OutputLocation
    ) {
        logFile.writeText(logBuffer.toString())
        outputManager.copyToLocation(logFile, outputLocation, logFile.name)
    }

    private suspend fun cleanProfile(
        packageName: String,
        compileFilter: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        val api = getProp("ro.build.version.sdk").toIntOrNull() ?: return false
        if (!checkProfileFiles(packageName, log)) {
            return true
        }
        if (api == 34) {
            log(LogType.Command, "cmd package art clear-app-profiles $packageName")
            commandRunner.run("cmd package art clear-app-profiles $packageName")
            log(LogType.Command, "cmd package compile --reset $packageName")
            commandRunner.run("cmd package compile --reset $packageName")
            log(LogType.Command, "cmd package compile -m ${compileFilter}-profile -f $packageName")
            return commandRunner.run("cmd package compile -m ${compileFilter}-profile -f $packageName").isSuccess
        }
        log(LogType.Command, "cmd package compile -c -m ${compileFilter}-profile -f $packageName")
        return commandRunner.run("cmd package compile -c -m ${compileFilter}-profile -f $packageName").isSuccess
    }

    private suspend fun profileCompile(
        packageName: String,
        compileFilter: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        if (!checkProfileFiles(packageName, log)) {
            return true
        }
        log(LogType.Command, "am force-stop $packageName")
        commandRunner.run("am force-stop $packageName")
        log(LogType.Command, "cmd package compile -m ${compileFilter}-profile -f $packageName")
        return commandRunner.run("cmd package compile -m ${compileFilter}-profile -f $packageName").isSuccess
    }

    private suspend fun waitForProfile(
        packageName: String,
        autoStart: Boolean,
        log: (LogType, String) -> Unit
    ): Boolean {
        val curFile = "/data/misc/profiles/cur/0/$packageName/primary.prof"
        if (autoStart) {
            val component = packageRepository.getLaunchComponent(packageName)
            val command = if (component.isNullOrBlank()) {
                "am start $packageName"
            } else {
                "am start -n $component"
            }
            log(LogType.Command, command)
            commandRunner.run(command)
        } else {
            log(LogType.Info, "请手动启动应用以生成热点")
        }
        repeat(120) {
            if (commandRunner.run("test -s $curFile").isSuccess) {
                log(LogType.Info, "热点文件已生成")
                commandRunner.run("am force-stop $packageName")
                return true
            }
            commandRunner.run("sleep 1")
        }
        log(LogType.Error, "等待热点超时")
        return false
    }

    private suspend fun checkMtManager(
        packageName: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        if (packageName != "bin.mt.plu" && packageName != "bin.mt.plu.caary") {
            return true
        }
        val pidResult = commandRunner.run("pidof $packageName")
        val pid = pidResult.stdout.firstOrNull().orEmpty()
        if (pid.isBlank()) {
            return true
        }
        val cpuset = commandRunner.run("cat /proc/$pid/cpuset").stdout.firstOrNull().orEmpty()
        if (cpuset.contains("top-app", ignoreCase = true)) {
            log(LogType.Error, "暂不支持在 MT 管理器编译自身")
            return false
        }
        return true
    }

    private suspend fun fullCompile(
        packageName: String,
        packagePaths: PackagePaths,
        options: CompileOptions,
        log: (LogType, String) -> Unit
    ): ExecutionResult {
        val env = resolveEnvironment(packagePaths, options, log) ?: return ExecutionResult(false, "环境检查失败")
        val tempDump = File(context.cacheDir, "oatdump_tmp.txt")
        if (!dumpOat(packagePaths.odex, tempDump.absolutePath, log)) {
            return ExecutionResult(false, "dump 失败，无法继续编译")
        }
        val dumpContent = tempDump.readText()
        val classLoaderContext = extractDumpValue(dumpContent, "class-loader-context=")
        val targetSdkVersion = extractDumpValue(dumpContent, "target-sdk-version:")
        val comments = if (env.api == 34) extractDumpValue(dumpContent, "comments=") else ""
        if (classLoaderContext.isBlank() || targetSdkVersion.isBlank()) {
            return ExecutionResult(false, "dump 信息不完整")
        }
        val dex2OatCommand = resolveDex2OatCommand(env.api, log) ?: return ExecutionResult(false, "dex2oat 未找到")
        var compileFilter = options.compileFilter
        if (options.forceCompile) {
            if (!prepareForceProfile(packageName, log)) {
                return ExecutionResult(false, "强制 profile 失败")
            }
            compileFilter = "speed-profile"
        }
        val compileCommands = buildDex2OatCommands(
            dex2OatCommand,
            packageName,
            packagePaths,
            env.copy(
                classLoaderContext = classLoaderContext,
                targetSdkVersion = targetSdkVersion,
                comments = comments
            ),
            options.copy(compileFilter = compileFilter)
        )
        removeOutputFiles(packagePaths, log)
        for (command in compileCommands) {
            log(LogType.Command, command)
            val result = commandRunner.run(command)
            if (!result.isSuccess) {
                log(LogType.Error, result.output)
                return ExecutionResult(false, "dex2oat 编译失败")
            }
        }
        setOutputPermissions(packagePaths, log)
        if (options.forceCompile) {
            if (waitForProfile(packageName, options.autoStart, log)) {
                if (!copyProfileForForce(packageName, log)) {
                    return ExecutionResult(false, "强制 profile 复制失败")
                }
                val retryCommands = buildDex2OatCommands(
                    dex2OatCommand,
                    packageName,
                    packagePaths,
                    env.copy(
                        classLoaderContext = classLoaderContext,
                        targetSdkVersion = targetSdkVersion,
                        comments = comments
                    ),
                    options
                )
                removeOutputFiles(packagePaths, log)
                for (command in retryCommands) {
                    log(LogType.Command, command)
                    val result = commandRunner.run(command)
                    if (!result.isSuccess) {
                        log(LogType.Error, result.output)
                        return ExecutionResult(false, "强制重编译失败")
                    }
                }
                setOutputPermissions(packagePaths, log)
            } else {
                return ExecutionResult(false, "强制等待热点失败")
            }
        }
        return ExecutionResult(true, "编译成功")
    }

    private suspend fun prepareForceProfile(
        packageName: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        val refFile = "/data/misc/profiles/ref/$packageName/primary.prof"
        log(LogType.Command, "touch $refFile")
        if (!commandRunner.run("touch $refFile").isSuccess) return false
        commandRunner.run("chown system:system $refFile")
        val api = getProp("ro.build.version.sdk").toIntOrNull() ?: 0
        val curFile = "/data/misc/profiles/cur/0/$packageName/primary.prof"
        if (api != 34) {
            commandRunner.run("touch $curFile")
            commandRunner.run("chown system:system $curFile")
            commandRunner.run("true > $curFile")
        }
        return true
    }

    private suspend fun copyProfileForForce(
        packageName: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        val curFile = "/data/misc/profiles/cur/0/$packageName/primary.prof"
        val refFile = "/data/misc/profiles/ref/$packageName/primary.prof"
        log(LogType.Command, "cp -f $curFile $refFile")
        val result = commandRunner.run("cp -f $curFile $refFile")
        if (!result.isSuccess) return false
        val api = getProp("ro.build.version.sdk").toIntOrNull() ?: 0
        if (api == 34) {
            commandRunner.run("rm -f $curFile")
        } else {
            commandRunner.run("true > $curFile")
        }
        return true
    }

    private suspend fun extCompile(
        options: CompileOptions,
        log: (LogType, String) -> Unit
    ): ExecutionResult {
        val filter = options.extCompileFilter.ifBlank { options.compileFilter }
        val target = options.extPackageName.ifBlank { "" }
        if (target.isBlank()) {
            return ExecutionResult(false, "自定义编译包名为空")
        }
        val clearFlag = if (options.extClearProfile) "-c" else ""
        log(LogType.Command, "cmd package compile $clearFlag -m $filter -f $target")
        val result = commandRunner.run("cmd package compile $clearFlag -m $filter -f $target")
        return if (result.isSuccess) {
            ExecutionResult(true, "自定义编译完成")
        } else {
            ExecutionResult(false, "自定义编译失败")
        }
    }

    private suspend fun resolveDex2OatCommand(api: Int, log: (LogType, String) -> Unit): String? {
        val prefer32 = api <= 29
        if (prefer32) {
            val dex2oat = commandRunner.run("which dex2oat")
            if (dex2oat.isSuccess && dex2oat.stdout.isNotEmpty()) {
                return dex2oat.stdout.first()
            }
        }
        val dex2oat64 = commandRunner.run("which dex2oat64")
        if (dex2oat64.isSuccess && dex2oat64.stdout.isNotEmpty()) {
            return dex2oat64.stdout.first()
        }
        val dex2oat32 = commandRunner.run("which dex2oat32")
        if (dex2oat32.isSuccess && dex2oat32.stdout.isNotEmpty()) {
            return dex2oat32.stdout.first()
        }
        log(LogType.Error, "dex2oat 命令未找到")
        return null
    }

    private suspend fun resolvePackagePaths(
        packageName: String,
        log: (LogType, String) -> Unit
    ): PackagePaths? {
        val pmResult = commandRunner.run("pm path $packageName")
        if (!pmResult.isSuccess || pmResult.stdout.isEmpty()) {
            log(LogType.Error, "包名无效或未安装")
            return null
        }
        val paths = pmResult.stdout.map { it.removePrefix("package:") }
        val baseApkPaths = paths.filter { it.endsWith("/base.apk") }
        val isAab = paths.size > 1
        if (isAab && baseApkPaths.size != 1) {
            log(LogType.Error, "AAB 软件包含多个 base.apk，暂不支持")
            return null
        }
        val baseApk = baseApkPaths.firstOrNull() ?: paths.first()
        val isUserPackage = baseApk.startsWith("/data/app/")
        val isSystemPackage = !isUserPackage
        return if (isUserPackage) {
            val baseDir = baseApk.removeSuffix("/base.apk")
            val cmdPath = "$baseDir/oat"
            val oatDirs = commandRunner.run("ls $cmdPath").stdout
            val armCode = when {
                oatDirs.contains("arm64") && oatDirs.contains("arm") -> "two"
                oatDirs.contains("arm64") -> "arm64"
                oatDirs.contains("arm") -> "arm"
                else -> ""
            }
            if (armCode.isBlank()) {
                log(LogType.Error, "未找到 oat 子目录")
                return null
            }
            val cpuCode = if (armCode == "arm64") {
                getProp("dalvik.vm.isa.arm64.variant")
            } else {
                getProp("dalvik.vm.isa.arm.variant")
            }
            val odex = "$cmdPath/${if (armCode == "two") "arm64" else armCode}/base.odex"
            val vdex = "$cmdPath/${if (armCode == "two") "arm64" else armCode}/base.vdex"
            val art = "$cmdPath/${if (armCode == "two") "arm64" else armCode}/base.art"
            PackagePaths(
                isSystemPackage = false,
                isAab = isAab,
                dex = "$baseDir/base.apk",
                path = baseDir,
                cmdPath = cmdPath,
                odex = odex,
                vdex = vdex,
                art = art,
                armCode = armCode,
                cpuCode = cpuCode.ifBlank { "generic" }
            )
        } else {
            val sysApkName = baseApk.substringAfterLast("/")
            var odex = commandRunner.run(
                "find /data/dalvik-cache/ -type f -name \"*@$sysApkName@classes.dex\""
            ).stdout.firstOrNull().orEmpty()
            var vdex = commandRunner.run(
                "find /data/dalvik-cache/ -type f -name \"*@$sysApkName@classes.vdex\""
            ).stdout.firstOrNull().orEmpty()
            if (odex.isBlank() && vdex.isBlank()) {
                val sysOdexName = sysApkName.replace(".apk", ".odex")
                val candidate64 = "${baseApk.substringBeforeLast("/")}/oat/arm64/$sysOdexName"
                val candidate32 = "${baseApk.substringBeforeLast("/")}/oat/arm/$sysOdexName"
                if (fileExists(candidate64)) {
                    odex = candidate64
                    vdex = candidate64.replace(".odex", ".vdex")
                } else if (fileExists(candidate32)) {
                    odex = candidate32
                    vdex = candidate32.replace(".odex", ".vdex")
                } else {
                    log(LogType.Error, "系统软件 odex/vdex 未找到")
                    return null
                }
            }
            val art = odex.replace("classes.dex", "classes.art")
            val armCode = when {
                odex.contains("cache/arm64/") && odex.contains("cache/arm/") -> "two"
                odex.contains("cache/arm64/") -> "arm64"
                odex.contains("cache/arm/") -> "arm"
                else -> ""
            }
            val cpuCode = if (armCode == "arm64") {
                getProp("dalvik.vm.isa.arm64.variant")
            } else {
                getProp("dalvik.vm.isa.arm.variant")
            }
            PackagePaths(
                isSystemPackage = true,
                isAab = isAab,
                dex = baseApk,
                path = baseApk.substringBeforeLast("/"),
                cmdPath = "",
                odex = odex,
                vdex = vdex,
                art = art,
                armCode = armCode.ifBlank { "arm64" },
                cpuCode = cpuCode.ifBlank { "generic" }
            )
        }
    }

    private suspend fun resolveEnvironment(
        packagePaths: PackagePaths,
        options: CompileOptions,
        log: (LogType, String) -> Unit
    ): CompileEnvironment? {
        val api = getProp("ro.build.version.sdk").toIntOrNull() ?: return null
        val bootClasspath = getEnv("BOOTCLASSPATH")
        if (bootClasspath.isNullOrBlank()) {
            log(LogType.Error, "BOOTCLASSPATH 环境变量异常")
            return null
        }
        val dex2oatBootClasspath = getEnv("DEX2OATBOOTCLASSPATH")
        if (api >= 29 && dex2oatBootClasspath.isNullOrBlank()) {
            log(LogType.Error, "DEX2OATBOOTCLASSPATH 环境变量异常")
            return null
        }
        val dvdrss = getProp("dalvik.vm.dex2oat-resolve-startup-strings").ifBlank { "true" }
        val updatableBcp = getProp("dalvik.vm.dex2oat-updatable-bcp-packages-file")
            .ifBlank { "/system/etc/updatable-bcp-packages.txt" }
        val coreFeatures = getProp("dalvik.vm.isa.arm64.features").ifBlank { "default" }
        val imageFormat = getProp("dalvik.vm.appimageformat").ifBlank { "lz4" }
        val cpuCount = options.cpuCount ?: parseCpuCount(getCpuPresent())
        val cpuAffinity = options.cpuAffinity ?: buildCpuAffinity(cpuCount)
        log(LogType.Info, "CPU 核心数: $cpuCount")
        log(LogType.Info, "CPU 亲和度: $cpuAffinity")
        return CompileEnvironment(
            api = api,
            bootClasspath = bootClasspath,
            dex2oatBootClasspath = dex2oatBootClasspath.orEmpty(),
            dvdrss = dvdrss,
            updatableBcp = updatableBcp,
            coreFeatures = coreFeatures,
            imageFormat = imageFormat,
            cpuCount = cpuCount,
            cpuAffinity = cpuAffinity,
            classLoaderContext = "",
            targetSdkVersion = "",
            comments = "",
            cpuCode = packagePaths.cpuCode,
            armCode = packagePaths.armCode
        )
    }

    private suspend fun dumpOat(
        odexPath: String,
        outputPath: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        val oatdumpCheck = commandRunner.run("which oatdump")
        if (!oatdumpCheck.isSuccess || oatdumpCheck.stdout.isEmpty()) {
            log(LogType.Error, "oatdump 工具未找到")
            return false
        }
        val tempFile = File(outputPath)
        if (tempFile.exists()) tempFile.delete()
        var seconds = 0.1
        while (seconds <= 2.0) {
            val command = "timeout $seconds oatdump --oat-file=\"$odexPath\" --output=\"$outputPath\""
            log(LogType.Command, command)
            commandRunner.run(command)
            if (commandRunner.run("test -s \"$outputPath\"").isSuccess) {
                return true
            }
            seconds = (seconds + 0.1).coerceAtMost(2.0)
        }
        return false
    }

    private suspend fun removeOutputFiles(packagePaths: PackagePaths, log: (LogType, String) -> Unit) {
        val targets = mutableListOf(packagePaths.art, packagePaths.odex, packagePaths.vdex)
        if (packagePaths.armCode == "two") {
            targets += targets.map { it.replace("/arm64/", "/arm/") }
        }
        for (path in targets.filter { it.isNotBlank() }.distinct()) {
            log(LogType.Command, "rm -f $path")
            commandRunner.run("rm -f $path")
        }
    }

    private suspend fun setOutputPermissions(packagePaths: PackagePaths, log: (LogType, String) -> Unit) {
        val targets = mutableListOf(packagePaths.art, packagePaths.odex, packagePaths.vdex)
        if (packagePaths.armCode == "two") {
            targets += targets.map { it.replace("/arm64/", "/arm/") }
        }
        for (path in targets.filter { it.isNotBlank() }.distinct()) {
            log(LogType.Command, "chown system:system $path")
            commandRunner.run("chown system:system $path")
            log(LogType.Command, "chmod 0644 $path")
            commandRunner.run("chmod 0644 $path")
        }
    }

    private fun extractDumpValue(content: String, marker: String): String {
        val line = content.lineSequence().firstOrNull { it.contains(marker) } ?: return ""
        return line.substringAfter(marker).substringBefore(' ').trim()
    }

    private suspend fun getProp(name: String): String {
        val result = commandRunner.run("getprop $name")
        return result.stdout.firstOrNull().orEmpty().trim()
    }

    private suspend fun getEnv(name: String): String? {
        val result = commandRunner.run("printenv $name")
        return result.stdout.firstOrNull()?.trim()
    }

    private fun parseCpuCount(raw: String): Int {
        var count = 0
        raw.split(",").forEach { token ->
            val part = token.trim()
            if (part.contains("-")) {
                val (start, end) = part.split("-").mapNotNull { it.toIntOrNull() }
                if (end >= start) {
                    count += (end - start + 1)
                }
            } else {
                if (part.toIntOrNull() != null) count += 1
            }
        }
        return count.coerceAtLeast(1)
    }

    private suspend fun getCpuPresent(): String {
        val result = commandRunner.run("cat /sys/devices/system/cpu/present")
        return result.stdout.firstOrNull().orEmpty()
    }

    private suspend fun fileExists(path: String): Boolean {
        return commandRunner.run("test -f \"$path\"").isSuccess
    }

    private fun buildCpuAffinity(cpuCount: Int): String {
        return (0 until cpuCount).joinToString(",")
    }

    private suspend fun checkProfileFiles(
        packageName: String,
        log: (LogType, String) -> Unit
    ): Boolean {
        val useJit = getProp("dalvik.vm.usejitprofiles")
        if (useJit == "false") {
            log(LogType.Error, "dalvik.vm.usejitprofiles 为 false，profile 编译无意义")
            return false
        }
        val api = getProp("ro.build.version.sdk").toIntOrNull() ?: return false
        if (api <= 30) {
            val path = "/data/misc/profiles/cur/0/$packageName/primary.prof"
            val exists = commandRunner.run("test -f $path").isSuccess
            if (!exists) {
                log(LogType.Error, "CUR 热点文件未找到：$path")
                return false
            }
        }
        return true
    }

    private fun buildDex2OatCommands(
        dex2oatCommand: String,
        packageName: String,
        packagePaths: PackagePaths,
        env: CompileEnvironment,
        options: CompileOptions
    ): List<String> {
        val addOptions = buildAddOptions(env, packagePaths.isSystemPackage, options.extraDex2OatOptions)
        val armVariants = if (packagePaths.armCode == "two") listOf("arm", "arm64") else listOf(packagePaths.armCode)
        return armVariants.map { armCode ->
            val cpuCode = if (armCode == "arm64") getPropSync("dalvik.vm.isa.arm64.variant") else getPropSync("dalvik.vm.isa.arm.variant")
            val odex = packagePaths.odex.replace("/arm64/", "/$armCode/").replace("/arm/", "/$armCode/")
            val art = packagePaths.art.replace("/arm64/", "/$armCode/").replace("/arm/", "/$armCode/")
            val vdex = packagePaths.vdex.replace("/arm64/", "/$armCode/").replace("/arm/", "/$armCode/")
            listOf(
                dex2oatCommand,
                "--dex-file=\"${packagePaths.dex}\"",
                "--dex-location=\"${packagePaths.dex}\"",
                "--oat-file=\"$odex\"",
                "--oat-location=\"$odex\"",
                "--app-image-file=\"$art\"",
                "--profile-file=\"/data/misc/profiles/ref/$packageName/primary.prof\"",
                "--instruction-set=\"$armCode\"",
                "--instruction-set-variant=\"${cpuCode.ifBlank { env.cpuCode }}\"",
                "--instruction-set-features=${env.coreFeatures}",
                "--runtime-arg -Xms64m",
                "--runtime-arg -Xmx512m",
                "--compiler-backend=Optimizing",
                "--compiler-filter=${options.compileFilter}",
                "-j${env.cpuCount}",
                "--no-generate-debug-info",
                "--no-generate-mini-debug-info",
                "--image-format=${env.imageFormat}",
                "--very-large-app-threshold=2147483647",
                "--compact-dex-level=fast",
                "--runtime-arg -Xtarget-sdk-version:${env.targetSdkVersion}",
                "--classpath-dir=\"${packagePaths.path}\"",
                "--class-loader-context=\"${env.classLoaderContext}\"",
                addOptions
            ).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    private fun buildAddOptions(env: CompileEnvironment, isSystemPackage: Boolean, extraOptions: String): String {
        val apiOptions = when (env.api) {
            28 -> "--runtime-arg -Xhidden-api-checks"
            29 -> "--resolve-startup-const-strings=${env.dvdrss} --runtime-arg -Xbootclasspath:${env.dex2oatBootClasspath} --runtime-arg -Xhidden-api-policy:enabled"
            30 -> "--resolve-startup-const-strings=${env.dvdrss} --updatable-bcp-packages-file=${env.updatableBcp} --runtime-arg -Xbootclasspath:${env.dex2oatBootClasspath} --runtime-arg -Xhidden-api-policy:enabled"
            31 -> "--resolve-startup-const-strings=${env.dvdrss} --updatable-bcp-packages-file=${env.updatableBcp} --runtime-arg -Xbootclasspath:${env.bootClasspath} --runtime-arg -Xhidden-api-policy:enabled"
            32 -> "--resolve-startup-const-strings=${env.dvdrss} --updatable-bcp-packages-file=${env.updatableBcp} --runtime-arg -Xbootclasspath:${env.bootClasspath} --runtime-arg -Xhidden-api-policy:enabled --runtime-arg -Xdeny-art-apex-data-files"
            33 -> "--resolve-startup-const-strings=${env.dvdrss} --runtime-arg -Xbootclasspath:${env.bootClasspath} --runtime-arg -Xhidden-api-policy:enabled --runtime-arg -Xdeny-art-apex-data-files"
            34 -> "--resolve-startup-const-strings=${env.dvdrss} --runtime-arg -Xhidden-api-policy:enabled --comments=${env.comments}"
            else -> ""
        }
        val reason = if (isSystemPackage) "--compilation-reason=bg-dexopt" else "--compilation-reason=install"
        val sanitized = if (isSystemPackage) {
            apiOptions.replace(" --runtime-arg -Xhidden-api-policy:enabled", "")
                .replace(" --runtime-arg -Xhidden-api-checks", "")
        } else {
            apiOptions
        }
        return listOf(sanitized, reason, extraOptions.trim()).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun getPropSync(name: String): String {
        return try {
            Runtime.getRuntime().exec("getprop $name").inputStream.bufferedReader().readLine().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private data class PackagePaths(
        val isSystemPackage: Boolean,
        val isAab: Boolean,
        val dex: String,
        val path: String,
        val cmdPath: String,
        val odex: String,
        val vdex: String,
        val art: String,
        val armCode: String,
        val cpuCode: String
    )

    private data class CompileEnvironment(
        val api: Int,
        val bootClasspath: String,
        val dex2oatBootClasspath: String,
        val dvdrss: String,
        val updatableBcp: String,
        val coreFeatures: String,
        val imageFormat: String,
        val cpuCount: Int,
        val cpuAffinity: String,
        val classLoaderContext: String,
        val targetSdkVersion: String,
        val comments: String,
        val cpuCode: String,
        val armCode: String
    )
}
