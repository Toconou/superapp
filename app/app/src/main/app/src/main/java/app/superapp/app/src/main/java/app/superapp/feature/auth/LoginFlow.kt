package app.superapp.feature.auth

/*
 Connexion par numéro de téléphone, sur le modèle de WhatsApp :
 numéro -> confirmation du numéro -> code à 6 chiffres -> nom -> accueil.
*/

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.OTP
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

// ---------- Données : Supabase sert uniquement à l'authentification ----------

class AuthRepository(private val supabase: SupabaseClient) {
    suspend fun hasSession(): Boolean {
        supabase.auth.awaitInitialization()
        return supabase.auth.currentSessionOrNull() != null
    }

    suspend fun sendCode(phone: String) {
        supabase.auth.signInWith(OTP) { this.phone = phone }
    }

    suspend fun verify(phone: String, code: String) {
        supabase.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = phone, token = code)
    }

    suspend fun saveName(name: String) {
        supabase.auth.updateUser { data { put("name", name) } }
    }
}

// ---------- État et logique ----------

enum class Step { Phone, Code, Profile, Done }

data class LoginState(
    val step: Step = Step.Phone,
    val countryCode: String = "33",
    val number: String = "",
    val code: String = "",
    val name: String = "",
    val askConfirm: Boolean = false,
    val loading: Boolean = false,
    val resendIn: Int = 0,
    val error: String? = null,
) {
    val phone: String
        get() = "+" + countryCode.filter(Char::isDigit) + number.filter(Char::isDigit).trimStart('0')
}

class LoginViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    init {
        viewModelScope.launch {
            if (repo.hasSession()) _state.update { it.copy(step = Step.Done) }
        }
    }

    fun onCountry(v: String) = _state.update { it.copy(countryCode = v.take(4), error = null) }
    fun onNumber(v: String) = _state.update { it.copy(number = v.take(15), error = null) }
    fun onName(v: String) = _state.update { it.copy(name = v.take(25), error = null) }

    fun askConfirm() {
        if (_state.value.number.length < 6) {
            _state.update { it.copy(error = "Entrez votre numéro de téléphone complet.") }
        } else {
            _state.update { it.copy(askConfirm = true) }
        }
    }

    fun dismissConfirm() = _state.update { it.copy(askConfirm = false) }

    fun sendCode() {
        _state.update { it.copy(askConfirm = false, loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.sendCode(_state.value.phone) }
                .onSuccess {
                    _state.update { it.copy(step = Step.Code, loading = false, code = "") }
                    startCountdown()
                }
                .onFailure {
                    _state.update {
                        it.copy(loading = false, error = "Impossible d'envoyer le code. Vérifiez le numéro et la connexion.")
                    }
                }
        }
    }

    fun onCode(v: String) {
        val digits = v.filter(Char::isDigit).take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) verify(digits) // vérification automatique, comme WhatsApp
    }

    private fun verify(code: String) {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { repo.verify(_state.value.phone, code) }
                .onSuccess { _state.update { it.copy(step = Step.Profile, loading = false) } }
                .onFailure {
                    _state.update { it.copy(loading = false, code = "", error = "Code incorrect. Vérifiez les 6 chiffres.") }
                }
        }
    }

    fun changeNumber() = _state.update { it.copy(step = Step.Phone, code = "", error = null) }

    fun resend() {
        if (_state.value.resendIn == 0) sendCode()
    }

    private fun startCountdown() {
        viewModelScope.launch {
            for (s in 60 downTo 1) {
                _state.update { it.copy(resendIn = s) }
                delay(1000)
            }
            _state.update { it.copy(resendIn = 0) }
        }
    }

    fun saveName() {
        val name = _state.value.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = "Entrez votre nom.") }
            return
        }
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching { repo.saveName(name) }
            _state.update { it.copy(step = Step.Done, loading = false) }
        }
    }
}

// ---------- Écrans ----------

private val WaGreen = Color(0xFF00A884)

@Composable
fun LoginScreen(vm: LoginViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.step) { if (s.step == Step.Done) onDone() }

    Column(
        Modifier.fillMaxSize().padding(24.dp).systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (s.step) {
            Step.Phone -> PhoneStep(s, vm)
            Step.Code -> CodeStep(s, vm)
            Step.Profile -> ProfileStep(s, vm)
            Step.Done -> Unit
        }
        s.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }

    if (s.askConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            text = { Text("Nous allons envoyer un code à ${s.phone}. Ce numéro est-il correct ?") },
            confirmButton = { TextButton(onClick = vm::sendCode) { Text("Continuer") } },
            dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("Modifier") } },
        )
    }
}

@Composable
private fun ColumnScope.PhoneStep(s: LoginState, vm: LoginViewModel) {
    Text("Entrez votre numéro de téléphone", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("Vous recevrez un code pour confirmer votre numéro.")
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("+")
        OutlinedTextField(
            value = s.countryCode, onValueChange = vm::onCountry, singleLine = true,
            modifier = Modifier.width(80.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = s.number, onValueChange = vm::onNumber, singleLine = true,
            label = { Text("Numéro de téléphone") }, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
    }
    Spacer(Modifier.weight(1f))
    PrimaryButton("Suivant", s.loading, vm::askConfirm)
}

@Composable
private fun CodeStep(s: LoginState, vm: LoginViewModel) {
    SmsAutoFill(onCode = vm::onCode)
    Text("Vérification de ${s.phone}", style = MaterialTheme.typography.headlineSmall)
    TextButton(onClick = vm::changeNumber) { Text("Numéro incorrect ?") }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = s.code, onValueChange = vm::onCode, singleLine = true,
        label = { Text("Code à 6 chiffres" ) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = vm::resend, enabled = s.resendIn == 0) {
        Text(if (s.resendIn > 0) "Renvoyer le code dans ${s.resendIn} s" else "Renvoyer le code")
    }
    if (s.loading) CircularProgressIndicator(color = WaGreen)
}

@Composable
private fun ColumnScope.ProfileStep(s: LoginState, vm: LoginViewModel) {
    Text("Votre profil", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = s.name, onValueChange = vm::onName, singleLine = true,
        label = { Text("Votre nom") }, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.weight(1f))
    PrimaryButton("Suivant", s.loading, vm::saveName)
}

/** Lit le code du SMS entrant (API Google SMS User Consent) et le renvoie à l'écran. */
@Composable
private fun SmsAutoFill(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
            Regex("\\d{6}").find(message)?.value?.let(onCode)
        }
    }
    DisposableEffect(Unit) {
        SmsRetriever.getClient(context).startSmsUserConsent(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (SmsRetriever.SMS_RETRIEVED_ACTION != intent.action) return
                val extras = intent.extras ?: return
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status
                if (status?.statusCode == CommonStatusCodes.SUCCESS) {
                    @Suppress("DEPRECATION")
                    extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT)?.let { launcher.launch(it) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
            SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
}

@Composable
private fun PrimaryButton(label: String, loading: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = !loading,
        colors = ButtonDefaults.buttonColors(containerColor = WaGreen),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) { Text(label) }
}
