package app.superapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.superapp.feature.auth.AuthRepository
import app.superapp.feature.auth.LoginScreen
import app.superapp.feature.auth.LoginViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient

object SupabaseProvider {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_KEY,
        ) {
            install(Auth)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_KEY.isBlank()) {
                        Text(
                            "Configuration manquante : ajoutez les secrets SUPABASE_URL et " +
                                "SUPABASE_ANON_KEY dans GitHub, puis recompilez.",
                            Modifier.padding(24.dp),
                        )
                    } else {
                        val vm: LoginViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer { LoginViewModel(AuthRepository(SupabaseProvider.client)) }
                            },
                        )
                        var connected by remember { mutableStateOf(false) }
                        if (connected) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Connecté. La messagerie arrive à l'étape suivante.")
                            }
                        } else {
                            LoginScreen(vm) { connected = true }
                        }
                    }
                }
            }
        }
    }
}
