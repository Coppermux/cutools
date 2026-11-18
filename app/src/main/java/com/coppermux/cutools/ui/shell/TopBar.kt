package com.coppermux.cutools.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.ui.theme.success

/**
 * Phase 17 — top address bar with the top-right hamburger menu.
 *
 * The address field accepts a URL and executes on Go / Enter ([ShellViewModel.navigate]),
 * which hides the Home Workspace grid and reveals the active web viewport.
 * The hamburger menu hosts the operational entries: Reset App action,
 * Dark/Light theme toggle, and About action.
 */
@Composable
fun TopBar(
    viewModel: ShellViewModel,
    state: ShellUiState,
    onUrlBarFocusChanged: ((Boolean) -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Surface(
        modifier = Modifier.statusBarsPadding(),
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.addressQuery,
                onValueChange = viewModel::updateAddress,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        onUrlBarFocusChanged?.invoke(focusState.isFocused)
                    },
                singleLine = true,
                placeholder = {
                    Text(
                        text = "Enter URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                leadingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.isNetworkDropped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.success),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val isHttps = state.addressQuery.startsWith("https://") || (!state.addressQuery.startsWith("http://") && state.addressQuery.isNotBlank())
                        Icon(
                            imageVector = if (isHttps) Icons.Filled.Lock else Icons.Filled.Public,
                            contentDescription = if (isHttps) "HTTPS" else "HTTP",
                            tint = if (isHttps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                },
                trailingIcon = {
                    if (state.addressQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.updateAddress("") },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        viewModel.navigate()
                    },
                ),
            )
            HamburgerMenu(
                viewModel = viewModel,
                state = state,
            )
        }
    }
}

@Composable
private fun HamburgerMenu(
    viewModel: ShellViewModel,
    state: ShellUiState,
) {
    Box {
        IconButton(
            onClick = { viewModel.setMenuOpen(!state.menuOpen) },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(
            expanded = state.menuOpen,
            onDismissRequest = { viewModel.setMenuOpen(false) },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Save Session",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    viewModel.setMenuOpen(false)
                    viewModel.openSaveCategoryChoice()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Reset App",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    viewModel.resetApp()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Restore F12 Button",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    viewModel.restoreF12Button()
                    viewModel.setMenuOpen(false)
                },
            )
            MenuSwitchItem(
                icon = if (state.useDarkTheme) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                label = if (state.useDarkTheme) "Dark Theme" else "Light Theme",
                checked = state.useDarkTheme,
                onCheckedChange = viewModel::toggleTheme,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            MenuSwitchItem(
                icon = Icons.Filled.WifiOff,
                label = "Drop Connection",
                checked = state.isNetworkDropped,
                onCheckedChange = { viewModel.setNetworkDrop(!state.isNetworkDropped) },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "About CuTools",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    viewModel.showAbout()
                    viewModel.setMenuOpen(false)
                },
            )
        }
    }
}

@Composable
private fun MenuSwitchItem(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    DropdownMenuItem(
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = { onCheckedChange() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        },
        onClick = { onCheckedChange() },
    )
}