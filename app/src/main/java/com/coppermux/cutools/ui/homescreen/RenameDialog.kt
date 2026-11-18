package com.coppermux.cutools.ui.homescreen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.ui.theme.CuToolsTheme

/** Rename-session dialog pre-filled with the current session name. */
@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val text = remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(
                text = "Rename Session",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            OutlinedTextField(
                value = text.value,
                onValueChange = { text.value = it },
                singleLine = true,
                label = { Text("Session Name") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(text.value.trim())
                    onDismiss()
                },
                enabled = text.value.isNotBlank(),
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun RenameDialogPreview() {
    CuToolsTheme {
        RenameDialog(
            currentName = "Example Session",
            onConfirm = {},
            onDismiss = {},
        )
    }
}