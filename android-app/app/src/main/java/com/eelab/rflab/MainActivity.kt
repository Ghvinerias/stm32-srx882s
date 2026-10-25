package com.eelab.rflab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eelab.rflab.ui.RfLabApp
import com.eelab.rflab.ui.theme.RfLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RfLabTheme {
                RfLabApp()
            }
        }
    }
}
