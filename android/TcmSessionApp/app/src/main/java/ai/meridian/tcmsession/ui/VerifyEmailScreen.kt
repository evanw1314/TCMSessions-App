package ai.meridian.tcmsession.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ai.meridian.tcmsession.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A client-side guard against double-tapping Resend. */
private const val RESEND_COOLDOWN_SECONDS = 30

/**
 * The email-verification screen. Kept free of ApiClient and Intent
 * so the screen stays previewable and the network plus navigation remain in the
 * Activity: [onVerify] and [onResend] return null on success, or the message to
 * show in the error slot.
 */
@Composable
fun VerifyEmailScreen(
    subtitle: String,
    onVerify: suspend (code: String) -> String?,
    onResend: suspend () -> String?,
    onUseDifferentEmail: () -> Unit,
) {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()

    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var cooldownLeft by rememberSaveable { mutableIntStateOf(RESEND_COOLDOWN_SECONDS) }
    var verifying by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    // Cancelled with the composition automatically, and rememberSaveable
    // keeps a rotation from restarting the cooldown.
    LaunchedEffect(cooldownLeft) {
        if (cooldownLeft > 0) {
            delay(1000)
            cooldownLeft--
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 440.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Verify your email",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 13.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 26.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = colors.surface1),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "6-digit code",
                        modifier = Modifier.padding(bottom = 4.dp),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )

                    CodeField(
                        code = code,
                        onCodeChange = { code = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    )

                    Button(
                        onClick = {
                            val entered = code.trim()
                            if (entered.length != 6) {
                                error = "Enter the 6-digit code"
                                return@Button
                            }
                            error = ""
                            verifying = true
                            scope.launch {
                                val message = onVerify(entered)
                                if (message != null) error = message
                                verifying = false
                            }
                        },
                        enabled = !verifying,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                    ) {
                        Text("Verify")
                    }

                    val canResend = cooldownLeft <= 0 && !resending
                    TextButton(
                        onClick = {
                            error = ""
                            resending = true
                            scope.launch {
                                val message = onResend()
                                if (message != null) {
                                    error = message
                                } else {
                                    cooldownLeft = RESEND_COOLDOWN_SECONDS
                                }
                                resending = false
                            }
                        },
                        enabled = canResend,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 14.dp),
                    ) {
                        Text(
                            text = if (cooldownLeft > 0) {
                                "Resend code (${cooldownLeft}s)"
                            } else {
                                "Resend code"
                            },
                            fontSize = 13.sp,
                            color = if (canResend) colors.accent else colors.textMuted,
                        )
                    }

                    if (error.isNotEmpty()) {
                        Text(
                            text = error,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            fontSize = 12.5.sp,
                            color = colors.critical,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            TextButton(
                onClick = onUseDifferentEmail,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(
                    text = "Use a different email",
                    fontSize = 12.5.sp,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * The app's text-field look (drawable/bg_field) and constraints: digits only, 6
 * max, centered and letter-spaced. BasicTextField rather than
 * OutlinedTextField because the latter's built-in label slot and 56dp minimum
 * height do not match bg_field's flat, 12dp-padded box.
 */
@Composable
private fun CodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    val shape = RoundedCornerShape(dimensionResource(R.dimen.radius_sm))
    Box(
        modifier = modifier
            .background(colors.surface1, shape)
            .border(1.dp, colors.border, shape)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = code,
            onValueChange = { raw -> onCodeChange(raw.filter { it.isDigit() }.take(6)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontSize = 20.sp,
                letterSpacing = 0.3.em,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (code.isEmpty()) {
                        Text(
                            text = "000000",
                            fontSize = 20.sp,
                            letterSpacing = 0.3.em,
                            color = colors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    inner()
                }
            },
        )
    }
}
