package com.urmindkilla.svodkalocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.urmindkilla.svodkalocal.ui.AppRoot
import com.urmindkilla.svodkalocal.ui.theme.SvodkaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SvodkaTheme {
                AppRoot()
            }
        }
    }
}
