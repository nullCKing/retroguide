package com.retroguide.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.retroguide.ui.theme.GuideTheme

/**
 * First-launch sign-in.
 *
 * Credentials are typed here and stored encrypted on the device. Nothing is compiled into the APK
 * and nothing is committed, which is why there is no "demo account" shortcut on this screen.
 */
@Composable
fun LoginScreen(
    theme: GuideTheme,
    error: String?,
    isSigningIn: Boolean,
    credentialsEncrypted: Boolean,
    onSubmit: (server: String, username: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var server by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val serverFocus = remember { FocusRequester() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.panel)
                .border(1.dp, theme.panelEdge, RoundedCornerShape(8.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Sign in to your provider",
                color = theme.infoTitle,
                fontSize = theme.titleSize,
                fontFamily = theme.fontFamily,
                fontWeight = theme.titleWeight,
            )

            LabelledField(
                label = "Server URL",
                value = server,
                onValueChange = { server = it },
                placeholder = "http://example.com:8080",
                theme = theme,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.focusRequester(serverFocus),
            )
            LabelledField(
                label = "Username",
                value = username,
                onValueChange = { username = it },
                theme = theme,
            )
            LabelledField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                theme = theme,
                isPassword = true,
                imeAction = ImeAction.Done,
                onDone = { onSubmit(server, username, password) },
            )

            if (server.trim().startsWith("http://")) {
                Text(
                    text = "This server uses plain HTTP, so your username and password travel " +
                        "unencrypted over the network.",
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }

            if (!credentialsEncrypted) {
                Text(
                    text = "This device's secure keystore is unavailable, so credentials will be " +
                        "stored unencrypted on the device.",
                    color = theme.highlight,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = theme.highlight,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = if (isSigningIn) "Checking…" else "Press SELECT on Password to sign in",
                color = theme.infoDetail,
                fontSize = theme.sectionSize,
                fontFamily = theme.fontFamily,
            )
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    theme: GuideTheme,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = theme.infoDetail,
            fontSize = theme.sectionSize,
            fontFamily = theme.fontFamily,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(theme.background)
                .border(1.dp, theme.panelEdge, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.cellText,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                ),
                cursorBrush = SolidColor(theme.highlight),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    color = theme.infoDetail,
                    fontSize = theme.detailSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}

/** Shown while the first import runs. */
@Composable
fun ImportScreen(
    theme: GuideTheme,
    message: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Loading channels",
                color = theme.infoTitle,
                fontSize = theme.titleSize,
                fontFamily = theme.fontFamily,
                fontWeight = theme.titleWeight,
            )
            Text(
                text = message,
                color = theme.cellText,
                fontSize = theme.detailSize,
                fontFamily = theme.fontFamily,
            )
            detail?.let {
                Text(
                    text = it,
                    color = theme.infoDetail,
                    fontSize = theme.sectionSize,
                    fontFamily = theme.fontFamily,
                )
            }
        }
    }
}
