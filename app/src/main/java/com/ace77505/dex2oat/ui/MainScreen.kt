package com.ace77505.dex2oat.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.ace77505.dex2oat.R
import com.ace77505.dex2oat.model.CompileOptions
import com.ace77505.dex2oat.model.LogEntry
import com.ace77505.dex2oat.model.LogType
import com.ace77505.dex2oat.model.OutputLocation
import com.ace77505.dex2oat.model.PackageItem
import com.ace77505.dex2oat.ui.theme.Dex2OatTheme

@Composable
fun MainScreen(
    uiState: MainUiState,
    onPackageSelected: (PackageItem) -> Unit,
    onOptionsChange: (CompileOptions) -> Unit,
    onOutputLocationChange: (OutputLocation) -> Unit,
    onSafUriSelected: (Uri) -> Unit,
    onRun: () -> Unit,
    contentPadding: PaddingValues
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            onSafUriSelected(uri)
        }
    }
    val filteredPackages = remember(
        uiState.packages,
        uiState.appFilter,
        uiState.appSort,
        uiState.appSortReversed,
        uiState.appSearchQuery
    ) {
        val query = uiState.appSearchQuery.trim()
        val baseComparator = when (uiState.appSort) {
            AppSort.UpdateTime -> compareByDescending<PackageItem> { it.lastUpdateTime }
                .thenBy { it.label.lowercase() }
            AppSort.ApkSize -> compareByDescending<PackageItem> { it.apkSize }
                .thenBy { it.label.lowercase() }
        }
        val comparator = if (uiState.appSortReversed) baseComparator.reversed() else baseComparator
        uiState.packages.asSequence()
            .filter { item ->
                when (uiState.appFilter) {
                    AppFilter.All -> true
                    AppFilter.User -> !(item.isSystem || item.isUpdatedSystem)
                    AppFilter.System -> item.isSystem || item.isUpdatedSystem
                }
            }
            .filter { item ->
                query.isBlank()
                    || item.label.contains(query, ignoreCase = true)
                    || item.packageName.contains(query, ignoreCase = true)
            }
            .sortedWith(comparator)
            .toList()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        RootStatus(uiState.rootAvailable)
        PackagePicker(
            packages = filteredPackages,
            selectedPackage = uiState.selectedPackage,
            appFilter = uiState.appFilter,
            onFilterChange = uiState::copy,
            appSort = uiState.appSort,
            appSortReversed = uiState.appSortReversed,
            appSearchQuery = uiState.appSearchQuery,
            onPackageSelected = onPackageSelected
        )
        OptionSection(
            options = uiState.compileOptions,
            onOptionsChange = onOptionsChange
        )
        OutputLocationSection(
            outputLocation = uiState.outputLocation,
            onOutputLocationChange = onOutputLocationChange,
            onPickSaf = { launcher.launch(null) }
        )
        AdvancedOptionsSection(
            options = uiState.compileOptions,
            onOptionsChange = onOptionsChange
        )
        Button(
            onClick = onRun,
            enabled = !uiState.isRunning && uiState.rootAvailable != false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (uiState.isRunning) stringResource(R.string.running) else stringResource(R.string.start))
        }
        uiState.lastMessageRes?.let { resId ->
            Text(text = stringResource(resId), color = MaterialTheme.colorScheme.primary)
        }
        LogSection(uiState.logs)
    }
}

@Composable
private fun RootStatus(rootAvailable: Boolean?) {
    val text = when (rootAvailable) {
        true -> stringResource(R.string.root_available)
        false -> stringResource(R.string.root_unavailable)
        null -> stringResource(R.string.root_checking)
    }
    Text(text = text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackagePicker(
    packages: List<PackageItem>,
    selectedPackage: PackageItem?,
    appFilter: AppFilter,
    appSort: AppSort,
    appSortReversed: Boolean,
    appSearchQuery: String,
    onFilterChange: (AppFilter) -> Unit,
    onSortChange: (AppSort) -> Unit,
    onSortReversedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPackageSelected: (PackageItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedPackage?.let { "${it.label} (${it.packageName})" } ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.package_label)) },
                leadingIcon = {
                    selectedPackage?.icon?.let { icon ->
                        Image(
                            bitmap = icon,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                packages.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                item.icon?.let { icon ->
                                    Image(
                                        bitmap = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(text = "${item.label} (${item.packageName})")
                            }
                        },
                        onClick = {
                            onPackageSelected(item)
                            expanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = appSearchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text(stringResource(R.string.search_app)) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(text = stringResource(R.string.filter_title), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appFilter == AppFilter.All, onClick = { onFilterChange(AppFilter.All) })
            Text(text = stringResource(R.string.filter_all))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appFilter == AppFilter.User, onClick = { onFilterChange(AppFilter.User) })
            Text(text = stringResource(R.string.filter_user))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appFilter == AppFilter.System, onClick = { onFilterChange(AppFilter.System) })
            Text(text = stringResource(R.string.filter_system))
        }
        Text(text = stringResource(R.string.sort_title), style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appSort == AppSort.UpdateTime, onClick = { onSortChange(AppSort.UpdateTime) })
            Text(text = stringResource(R.string.sort_update_time))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = appSort == AppSort.ApkSize, onClick = { onSortChange(AppSort.ApkSize) })
            Text(text = stringResource(R.string.sort_apk_size))
        }
        CheckboxOption(
            label = stringResource(R.string.sort_reverse),
            checked = appSortReversed,
            onCheckedChange = onSortReversedChange
        )
    }
    if (selectedPackage != null) {
        val tags = buildList {
            if (selectedPackage.isSystem) add(stringResource(R.string.system_app))
            if (selectedPackage.isUpdatedSystem) add(stringResource(R.string.updated_system_app))
            if (selectedPackage.isAab) add(stringResource(R.string.aab_app))
        }
        if (tags.isNotEmpty()) {
            Text(text = tags.joinToString(" / "))
        }
    }
}

@Composable
private fun OptionSection(
    options: CompileOptions,
    onOptionsChange: (CompileOptions) -> Unit
) {
    SectionTitle(stringResource(R.string.basic_options))
    CheckboxOption(
        label = stringResource(R.string.option_clean_profile),
        checked = options.doCleanProfile,
        onCheckedChange = { onOptionsChange(options.copy(doCleanProfile = it)) }
    )
    CheckboxOption(
        label = stringResource(R.string.option_profile),
        checked = options.doProfile,
        onCheckedChange = { onOptionsChange(options.copy(doProfile = it)) }
    )
    if (options.doCleanProfile && options.doProfile) {
        CheckboxOption(
            label = stringResource(R.string.option_auto_profile),
            checked = options.autoProfile,
            onCheckedChange = { onOptionsChange(options.copy(autoProfile = it)) }
        )
        if (options.autoProfile) {
            CheckboxOption(
                label = stringResource(R.string.option_auto_start),
                checked = options.autoStart,
                onCheckedChange = { onOptionsChange(options.copy(autoStart = it)) }
            )
        }
    }
    CheckboxOption(
        label = stringResource(R.string.option_full_compile),
        checked = options.doCompile,
        onCheckedChange = { onOptionsChange(options.copy(doCompile = it)) }
    )
    CheckboxOption(
        label = stringResource(R.string.option_force_compile),
        checked = options.forceCompile,
        onCheckedChange = { onOptionsChange(options.copy(forceCompile = it)) }
    )
    CheckboxOption(
        label = stringResource(R.string.option_dump),
        checked = options.doDumpOnly,
        onCheckedChange = { onOptionsChange(options.copy(doDumpOnly = it)) }
    )
    CheckboxOption(
        label = stringResource(R.string.option_ext_compile),
        checked = options.doExtCompile,
        onCheckedChange = { onOptionsChange(options.copy(doExtCompile = it)) }
    )
}

@Composable
private fun OutputLocationSection(
    outputLocation: OutputLocation,
    onOutputLocationChange: (OutputLocation) -> Unit,
    onPickSaf: () -> Unit
) {
    SectionTitle(stringResource(R.string.output_location))
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = outputLocation is OutputLocation.AppPrivate,
            onClick = { onOutputLocationChange(OutputLocation.AppPrivate) }
        )
        Text(text = stringResource(R.string.output_internal))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
            selected = outputLocation is OutputLocation.Saf,
            onClick = { onPickSaf() }
        )
        Text(text = stringResource(R.string.output_saf))
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onPickSaf) {
            Text(text = stringResource(R.string.choose_directory))
        }
    }
    if (outputLocation is OutputLocation.Saf) {
        Text(text = stringResource(R.string.current_directory, outputLocation.uri.toString()))
    }
}

@Composable
private fun AdvancedOptionsSection(
    options: CompileOptions,
    onOptionsChange: (CompileOptions) -> Unit
) {
    SectionTitle(stringResource(R.string.advanced_options))
    CheckboxOption(
        label = stringResource(R.string.option_include_bootclasspath),
        checked = options.includeBootClasspath,
        onCheckedChange = { onOptionsChange(options.copy(includeBootClasspath = it)) }
    )
    OutlinedTextField(
        value = options.extraDex2OatOptions,
        onValueChange = { onOptionsChange(options.copy(extraDex2OatOptions = it)) },
        label = { Text(stringResource(R.string.extra_options)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = options.cpuCount?.toString().orEmpty(),
        onValueChange = { value ->
            val count = value.toIntOrNull()
            onOptionsChange(options.copy(cpuCount = count))
        },
        label = { Text(stringResource(R.string.cpu_count)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = options.cpuAffinity.orEmpty(),
        onValueChange = { onOptionsChange(options.copy(cpuAffinity = it)) },
        label = { Text(stringResource(R.string.cpu_affinity)) },
        modifier = Modifier.fillMaxWidth()
    )
    if (options.doExtCompile) {
        OutlinedTextField(
            value = options.extPackageName,
            onValueChange = { onOptionsChange(options.copy(extPackageName = it)) },
            label = { Text(stringResource(R.string.ext_package_name)) },
            modifier = Modifier.fillMaxWidth()
        )
        CheckboxOption(
            label = stringResource(R.string.ext_clear_profile),
            checked = options.extClearProfile,
            onCheckedChange = { onOptionsChange(options.copy(extClearProfile = it)) }
        )
    }
}

@Composable
private fun LogSection(logs: List<LogEntry>) {
    SectionTitle(stringResource(R.string.logs))
    val text = logs.joinToString("\n") { entry -> "[${entry.type}] ${entry.message}" }
    OutlinedTextField(
        value = text,
        onValueChange = {},
        readOnly = true,
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun CheckboxOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange)
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = label)
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    val placeholderIcon = ImageBitmap(1, 1)
    Dex2OatTheme {
        MainScreen(
            uiState = MainUiState(
                packages = listOf(
                    PackageItem(
                        "com.example.app",
                        "Example App",
                        placeholderIcon,
                        lastUpdateTime = 0L,
                        apkSize = 1024L,
                        isSystem = false,
                        isUpdatedSystem = false,
                        isAab = false
                    ),
                    PackageItem(
                        "com.android.settings",
                        "Settings",
                        placeholderIcon,
                        lastUpdateTime = 0L,
                        apkSize = 2048L,
                        isSystem = true,
                        isUpdatedSystem = false,
                        isAab = false
                    )
                ),
                selectedPackage = PackageItem(
                    "com.example.app",
                    "Example App",
                    placeholderIcon,
                    lastUpdateTime = 0L,
                    apkSize = 1024L,
                    isSystem = false,
                    isUpdatedSystem = false,
                    isAab = false
                ),
                compileOptions = CompileOptions(),
                outputLocation = OutputLocation.AppPrivate,
                rootAvailable = true,
                logs = listOf(
                    LogEntry("Started", LogType.Info),
                    LogEntry("ls -l", LogType.Command)
                ),
                isRunning = false
            ),
            onPackageSelected = {},
            onOptionsChange = {},
            onOutputLocationChange = {},
            onSafUriSelected = {},
            onRun = {},
            contentPadding = PaddingValues(0.dp)
        )
    }
}
