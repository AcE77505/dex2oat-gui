package com.ace77505.dex2oat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ace77505.dex2oat.ui.Dex2OatApp
import com.ace77505.dex2oat.ui.theme.Dex2OatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Dex2OatTheme {
                Dex2OatApp()
            }
        }
    }
}
