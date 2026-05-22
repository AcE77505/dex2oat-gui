package com.ace77505.dex2oat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ace77505.dex2oat.data.OutputManager
import com.ace77505.dex2oat.data.SettingsRepository
import com.ace77505.dex2oat.domain.Dex2OatExecutor
import com.ace77505.dex2oat.domain.PackageManagerRepository
import com.ace77505.dex2oat.model.CompileOptions
import com.ace77505.dex2oat.model.LogEntry
import com.ace77505.dex2oat.model.OutputLocation
import com.ace77505.dex2oat.model.PackageItem
import com.ace77505.dex2oat.root.RootCommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val packages: List<PackageItem> = emptyList(),
    val selectedPackage: PackageItem? = null,
    val compileOptions: CompileOptions = CompileOptions(),
    val outputLocation: OutputLocation = OutputLocation.AppPrivate,
    val rootAvailable: Boolean? = null,
    val logs: List<LogEntry> = emptyList(),
    val isRunning: Boolean = false,
    val lastMessageRes: Int? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = SettingsRepository(application)
    private val outputManager = OutputManager(application)
    private val packageRepository = PackageManagerRepository(application.packageManager)
    private val rootRunner = RootCommandRunner()
    private val executor = Dex2OatExecutor(application, rootRunner, outputManager, packageRepository)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val packages = packageRepository.loadPackages()
            _uiState.update { it.copy(packages = packages, selectedPackage = packages.firstOrNull()) }
        }
        viewModelScope.launch {
            settingsRepository.outputLocation.collect { location ->
                _uiState.update { it.copy(outputLocation = location) }
            }
        }
        viewModelScope.launch {
            val rootAvailable = rootRunner.isRootAvailable()
            _uiState.update { it.copy(rootAvailable = rootAvailable) }
        }
    }

    fun selectPackage(item: PackageItem) {
        _uiState.update { it.copy(selectedPackage = item) }
    }

    fun updateCompileOptions(options: CompileOptions) {
        _uiState.update { it.copy(compileOptions = options) }
    }

    fun setOutputLocation(location: OutputLocation) {
        viewModelScope.launch {
            settingsRepository.setOutputLocation(location)
        }
    }

    fun setSafUri(uri: Uri) {
        viewModelScope.launch {
            settingsRepository.setSafUri(uri)
        }
    }

    fun runExecution() {
        val state = _uiState.value
        val packageName = state.selectedPackage?.packageName.orEmpty()
        val options = state.compileOptions
        if (packageName.isBlank()) {
            _uiState.update { it.copy(lastMessageRes = com.ace77505.dex2oat.R.string.error_select_package) }
            return
        }
        if (!options.doCleanProfile && !options.doProfile && !options.doCompile && !options.doDumpOnly && !options.doExtCompile) {
            _uiState.update { it.copy(lastMessageRes = com.ace77505.dex2oat.R.string.error_select_operation) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, logs = emptyList(), lastMessageRes = null) }
            val result = executor.execute(
                packageName = packageName,
                options = options,
                outputLocation = state.outputLocation
            ) { entry ->
                _uiState.update { current ->
                    current.copy(logs = current.logs + entry)
                }
            }
            val messageRes = if (result.success) {
                com.ace77505.dex2oat.R.string.execution_success
            } else {
                com.ace77505.dex2oat.R.string.execution_failed
            }
            _uiState.update { it.copy(isRunning = false, lastMessageRes = messageRes) }
        }
    }
}
