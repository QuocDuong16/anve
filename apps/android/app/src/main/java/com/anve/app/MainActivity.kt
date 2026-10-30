package com.anve.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anve.app.data.sos.IncomingPush
import com.anve.app.ui.AnVeApp
import com.anve.app.ui.theme.AnVeTheme

class MainActivity : ComponentActivity() {
    private var incomingPush by mutableStateOf<IncomingPush?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("anve_event_kind") == true) {
            incomingPush = IncomingPush(savedInstanceState.getString("anve_event_kind"), savedInstanceState.getString("anve_event_id"), savedInstanceState.getString("anve_event_owner"))
        } else readPush(intent)
        enableEdgeToEdge()
        val container = (application as AnVeApplication).container
        setContent {
            AnVeTheme {
                AnVeApp(container = container, incomingPush = incomingPush, onPushConsumed = { incomingPush = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPush(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        incomingPush?.let {
            outState.putString("anve_event_kind", it.kind)
            outState.putString("anve_event_id", it.eventId)
            outState.putString("anve_event_owner", it.ownerId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun readPush(intent: Intent?) {
        val kind = intent?.getStringExtra("anve_event_kind") ?: return
        incomingPush = IncomingPush(kind, intent.getStringExtra("anve_event_id"), intent.getStringExtra("anve_event_owner"))
        intent.removeExtra("anve_event_kind")
        intent.removeExtra("anve_event_id")
        intent.removeExtra("anve_event_owner")
    }
}
