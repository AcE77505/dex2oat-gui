package com.ace77505.dex2oat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun Dex2OatApp() {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        MainScreen(
            uiState = uiState,
            onPackageSelected = viewModel::selectPackage,
            onOptionsChange = viewModel::updateCompileOptions,
            onOutputLocationChange = viewModel::setOutputLocation,
            onSafUriSelected = viewModel::setSafUri,
            onRun = viewModel::runExecution,
            contentPadding = padding
        )
    }
}
