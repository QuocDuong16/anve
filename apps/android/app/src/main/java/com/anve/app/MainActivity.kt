package com.anve.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.anve.app.data.sos.IncomingPush
import com.anve.app.ui.AnVeApp
import com.anve.app.ui.theme.AnVeTheme
import com.anve.app.ui.update.AppUpdateCoordinator
import com.anve.app.ui.update.PlayAppUpdateGateway
import com.anve.app.ui.update.openPlayStoreListing

class MainActivity : ComponentActivity() {
    private var incomingPush by mutableStateOf<IncomingPush?>(null)

    private val updateCoordinator by lazy {
        AppUpdateCoordinator(
            scope = lifecycleScope,
            gateway = PlayAppUpdateGateway(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("anve_event_kind") == true) {
            incomingPush = IncomingPush(savedInstanceState.getString("anve_event_kind"), savedInstanceState.getString("anve_event_id"), savedInstanceState.getString("anve_event_owner"))
        } else readPush(intent)
        if (savedInstanceState == null) readInvitation(intent) else intent?.data = null
        enableEdgeToEdge()
        val container = (application as AnVeApplication).container
        setContent {
            val updateState by updateCoordinator.state.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current
            val updateLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                updateCoordinator.onUpdateFlowResult(result.resultCode)
            }
            DisposableEffect(lifecycleOwner, updateCoordinator, updateLauncher) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        updateCoordinator.onResume(updateLauncher)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            AnVeTheme {
                AnVeApp(
                    container = container,
                    incomingPush = incomingPush,
                    onPushConsumed = { incomingPush = null },
                    updateState = updateState,
                    onStartAppUpdate = { updateCoordinator.onConfirmUpdate(updateLauncher) },
                    onOpenPlayStore = {
                        if (!openPlayStoreListing(this@MainActivity)) {
                            updateCoordinator.onStoreUnavailable()
                        }
                    },
                    onExitForDeferredUpdate = { finishAndRemoveTask() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPush(intent)
        readInvitation(intent)
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

    private fun readInvitation(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        incomingPush = null
        val raw = intent.dataString
        intent.data = null
        if (raw != null) (application as AnVeApplication).container.invitationInbox.offer(raw)
    }
}
