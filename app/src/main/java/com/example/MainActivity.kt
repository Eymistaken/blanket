package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.audio.BlanketAudioService
import com.example.data.BlanketDatabase
import com.example.data.PresetRepository
import com.example.ui.BlanketApp
import com.example.ui.BlanketViewModel
import com.example.ui.BlanketViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: BlanketViewModel

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission granted or denied, play remains functional either way
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Room Database and Repository
        val database = BlanketDatabase.getDatabase(applicationContext)
        val repository = PresetRepository(database.presetDao())

        // 2. Create ViewModel
        val factory = BlanketViewModelFactory(applicationContext, repository)
        viewModel = ViewModelProvider(this, factory)[BlanketViewModel::class.java]

        // 3. Start & Bind the Audio Service with error handling
        bindAudioService()

        // 4. Request notification permissions dynamically on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                BlanketApp(
                    viewModel = viewModel,
                    onRetryServiceBind = { bindAudioService() }
                )
            }
        }
    }

    private fun bindAudioService() {
        val serviceIntent = Intent(this, BlanketAudioService::class.java)
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d("MainActivity", "startService failed, falling back to bindService", e)
            }
        }

        try {
            val bound = bindService(serviceIntent, viewModel.serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                if (BuildConfig.DEBUG) {
                    Log.d("MainActivity", "bindService returned false - service connection failed")
                }
                viewModel.setServiceBindError("Ses servisi başlatılamadı. Kontroller çalışmayabilir.")
            } else {
                viewModel.clearServiceBindError()
            }
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) {
                Log.d("MainActivity", "SecurityException during bindService", e)
            }
            viewModel.setServiceBindError("Güvenlik engeli nedeniyle ses servisi başlatılamadı.")
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) {
                Log.d("MainActivity", "IllegalStateException during bindService", e)
            }
            viewModel.setServiceBindError("Uygulama durumundan dolayı ses servisi başlatılamadı.")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d("MainActivity", "Exception during bindService: ${e.localizedMessage}", e)
            }
            viewModel.setServiceBindError("Ses servisine bağlanırken hata oluştu.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind the service to prevent binder connection leaks
        if (viewModel.isServiceConnected.value) {
            unbindService(viewModel.serviceConnection)
        }
    }
}
