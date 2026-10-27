package com.anyhand.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anyhand.app.ui.AnyhandApp
import com.anyhand.app.ui.theme.AnyhandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AnyhandTheme {
                AnyhandApp()
            }
        }
    }
}
