package com.nodaysidle.reverseuno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nodaysidle.reverseuno.core.Store
import com.nodaysidle.reverseuno.service.BubbleRuntime
import com.nodaysidle.reverseuno.ui.HubApp
import com.nodaysidle.reverseuno.ui.ReverseunoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        BubbleRuntime.start(this)
        setContent {
            val data by Store.data.collectAsStateWithLifecycle()
            ReverseunoTheme(data.settings.appearance.theme) { HubApp(data) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up the overlay permission when the user returns from system settings.
        BubbleRuntime.start(this)
    }
}
