package dev.varch.controller

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.varch.controller.ui.RemoteShell
import dev.varch.controller.ui.SetupScreen
import dev.varch.controller.ui.Surface

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // A restored activity has already handled the intent that started it.
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            LifecycleStartEffect(vm) {
                vm.start()
                onStopOrDispose { vm.stop() }
            }
            Surface {
                val remote = vm.remote
                if (remote == null) {
                    SetupScreen(
                        state = vm.setup,
                        onAddressChange = vm::onAddressChange,
                        onPairAddress = vm::pairWithAddress,
                        onPick = vm::beginPairing,
                        onCodeChange = vm::onCodeChange,
                        onCancel = vm::cancelPairing,
                    )
                } else {
                    RemoteShell(remote, vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Text shared to the app from another app's share sheet. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::share)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && vm.onVolumeKey(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyDown(keyCode, event)
    }

    // The release is swallowed too, or the phone shows its own volume panel.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isVolumeKey(keyCode) && vm.remote?.live == true) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}
