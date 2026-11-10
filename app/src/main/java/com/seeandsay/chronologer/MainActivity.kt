package com.seeandsay.chronologer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.seeandsay.chronologer.ui.ChronologerViewModel
import com.seeandsay.chronologer.ui.SeeAndSayDashboard

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChronologerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(
            this,
            ChronologerViewModel.factory(application),
        )[ChronologerViewModel::class.java]
        viewModel.attach(this)
        dispatchDatAuth(intent)
        setContent {
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { granted ->
                viewModel.onPermissionResult(granted)
            }
            LaunchedEffect(Unit) {
                val missing = RUNTIME_PERMISSIONS.filter { permission ->
                    ContextCompat.checkSelfPermission(this@MainActivity, permission) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    viewModel.onPermissionResult(RUNTIME_PERMISSIONS.associateWith { true })
                } else {
                    permissionLauncher.launch(RUNTIME_PERMISSIONS)
                }
            }
            SeeAndSayDashboard(
                viewModel = viewModel,
                onRequestPermissions = { permissionLauncher.launch(RUNTIME_PERMISSIONS) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::viewModel.isInitialized) {
            viewModel.attach(this)
            dispatchDatAuth(intent)
        }
    }

    private fun dispatchDatAuth(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "seeandsay" && data.host == "dat-auth") {
            viewModel.completeDatHandshake(this)
        }
    }

    private companion object {
        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
