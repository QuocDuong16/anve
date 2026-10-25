package com.anve.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.anve.app.ui.AnVeApp
import com.anve.app.ui.theme.AnVeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AnVeApplication).container
        setContent {
            AnVeTheme {
                AnVeApp(container = container)
            }
        }
    }
}
