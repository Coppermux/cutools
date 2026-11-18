package com.coppermux.cutools.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coppermux.cutools.core.console.ConsoleEntry
import com.coppermux.cutools.core.console.ConsoleEntryKind
import com.coppermux.cutools.core.console.ConsoleLifecyclePhase
import com.coppermux.cutools.core.console.ConsoleSeverity
import com.coppermux.cutools.core.console.ConsoleState
import com.coppermux.cutools.ui.theme.browserNavContainer
import com.coppermux.cutools.ui.theme.onBrowserNavContainer

/**
 * JavaScript Syntax Action definition for the compact vertical syntax-action strip.
 */
data class SyntaxAction(
    val symbol: String,
    val token: String,
    val isPaired: Boolean,
    val contentDescription: String,
)

val SyntaxActions = listOf(
    SyntaxAction(symbol = "\"\"", token = "\"\"", isPaired = true, contentDescription = "Double quotes"),
    SyntaxAction(symbol = "''", token = "''", isPaired = true, contentDescription = "Single quotes"),
    SyntaxAction(symbol = "()", token = "()", isPaired = true, contentDescription = "Parentheses"),
    SyntaxAction(symbol = "[]", token = "[]", isPaired = true, contentDescription = "Square brackets"),
    SyntaxAction(symbol = "{}", token = "{}", isPaired = true, contentDescription = "Curly braces"),
    SyntaxAction(symbol = "``", token = "``", isPaired = true, contentDescription = "Backticks"),
    SyntaxAction(symbol = ";", token = ";", isPaired = false, contentDescription = "Semicolon"),
    SyntaxAction(symbol = ":", token = ":", isPaired = false, contentDescription = "Colon"),
    SyntaxAction(symbol = ",", token = ",", isPaired = false, contentDescription = "Comma"),
    SyntaxAction(symbol = ".", token = ".", isPaired = false, contentDescription = "Dot"),
    SyntaxAction(symbol = "=>", token = "=>", isPaired = false, contentDescription = "Arrow operator"),
)

/**
 * Legacy JavaScript Syntax Shortcut definition.
 */
@Deprecated("Use SyntaxAction instead.")
data class SyntaxShortcut(
    val displayLabel: String,
    val token: String,
    val isPaired: Boolean,
)

@Deprecated("Use SyntaxActions instead.")
@Suppress("DEPRECATION")
private val SyntaxShortcuts = listOf(
    SyntaxShortcut("{}", "{}", true),
    SyntaxShortcut("()", "()", true),
    SyntaxShortcut("[]", "[]", true),
    SyntaxShortcut("\"\"", "\"\"", true),
    SyntaxShortcut("''", "''", true),
    SyntaxShortcut("=>", "=>", false),
    SyntaxShortcut("?.", "?.", false),
    SyntaxShortcut(";", ";", false),
    SyntaxShortcut(":", ":", false),
)

/**
 * Inserts a syntax action into the current [TextFieldValue].
 * If text is selected, the selected text is replaced by the token.
 * For paired tokens, cursor is placed between the two characters.
 * For unpaired tokens, cursor is placed immediately after the token.
 */
fun insertSyntaxAction(
    action: SyntaxAction,
    currentTfv: TextFieldValue,
): TextFieldValue {
    val text = currentTfv.text
    val start = currentTfv.selection.min.coerceIn(0, text.length)
    val end = currentTfv.selection.max.coerceIn(0, text.length)

    val newText = text.substring(0, start) + action.token + text.substring(end)
    val newCursorOffset = if (action.isPaired && action.token.length >= 2) {
        start + 1
    } else {
        start + action.token.length
    }
    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursorOffset),
    )
}

/**
 * Inserts a syntax shortcut into the current [TextFieldValue].
 * Replaces selected text if present.
 * For paired tokens, cursor is placed between the two characters.
 * For unpaired tokens, cursor is placed immediately after the token.
 */
fun insertSyntaxToken(
    token: String,
    isPaired: Boolean,
    currentTfv: TextFieldValue,
): TextFieldValue {
    val text = currentTfv.text
    val start = currentTfv.selection.min.coerceIn(0, text.length)
    val end = currentTfv.selection.max.coerceIn(0, text.length)

    val newText = text.substring(0, start) + token + text.substring(end)
    val newCursorOffset = if (isPaired && token.length >= 2) {
        start + 1
    } else {
        start + token.length
    }
    return TextFieldValue(
        text = newText,
        selection = TextRange(newCursorOffset),
    )
}

/**
 * Determines whether the console pill should respond to software keyboard (IME) insets.
 *
 * Rules:
 * - When the URL bar is active ([isUrlBarFocused] == true), the console pill must NOT respond
 *   to IME insets, remaining at its physical screen bottom rest position (covered by the keyboard).
 * - When the URL bar is not active, the console pill responds to IME insets if the console is
 *   focused ([isConsoleFocused]), expanded ([isExpanded]), or showing syntax actions ([isSyntaxMenuVisible]).
 */
fun shouldConsoleRespondToIme(
    isUrlBarFocused: Boolean,
    isConsoleFocused: Boolean,
    isExpanded: Boolean = false,
    isSyntaxMenuVisible: Boolean = false,
): Boolean {
    return !isUrlBarFocused && (isConsoleFocused || isExpanded || isSyntaxMenuVisible)
}

/**
 * Persistent bottom JavaScript Console shell surface.
 *
 * Implements the locked ChatGPT-style bottom input pill with compact syntax menu
 * and expandable console output overlay that remains above the IME/keyboard.
 */
@Composable
fun JavaScriptConsoleShell(
    viewModel: ShellViewModel,
    state: ShellUiState,
    isUrlBarFocused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val consoleState = state.consoleState
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var isSyntaxMenuVisible by rememberSaveable { mutableStateOf(false) }
    var isConsoleFocused by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // When URL bar is focused, close expanded history panel and syntax menu
    LaunchedEffect(isUrlBarFocused) {
        if (isUrlBarFocused) {
            isExpanded = false
            isSyntaxMenuVisible = false
        }
    }

    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = consoleState.input,
                selection = TextRange(consoleState.input.length),
            ),
        )
    }

    // Synchronize input when changed externally (e.g. history navigation)
    LaunchedEffect(consoleState.input) {
        if (consoleState.input != textFieldValue.text) {
            textFieldValue = TextFieldValue(
                text = consoleState.input,
                selection = TextRange(consoleState.input.length),
            )
        }
    }

    // Android Back handler: collapse syntax menu or console before navigating away
    BackHandler(enabled = isExpanded || isSyntaxMenuVisible) {
        if (isSyntaxMenuVisible) {
            isSyntaxMenuVisible = false
        } else if (isExpanded) {
            isExpanded = false
            focusManager.clearFocus()
        }
    }

    val shellModifier = if (isExpanded || isSyntaxMenuVisible) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxWidth()
    }

    // Console pill responds to IME only when console is focused/active and URL bar is not focused
    val shouldRespondToIme = shouldConsoleRespondToIme(
        isUrlBarFocused = isUrlBarFocused,
        isConsoleFocused = isConsoleFocused,
        isExpanded = isExpanded,
        isSyntaxMenuVisible = isSyntaxMenuVisible,
    )
    val insets = if (shouldRespondToIme) {
        WindowInsets.ime.union(WindowInsets.navigationBars)
    } else {
        WindowInsets.navigationBars
    }

    Box(
        modifier = shellModifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Scrim overlay when expanded
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            isExpanded = false
                            focusManager.clearFocus()
                        },
                    ),
            )
        } else if (isSyntaxMenuVisible) {
            // Transparent dismiss overlay when syntax menu is open (closes menu when tapping outside)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            isSyntaxMenuVisible = false
                        },
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(insets),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isExpanded) {
                ExpandedConsolePanel(
                    consoleState = consoleState,
                    onHistoryBack = viewModel::onConsoleHistoryBack,
                    onHistoryForward = viewModel::onConsoleHistoryForward,
                    onCollapse = {
                        isExpanded = false
                        focusManager.clearFocus()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                )
            }

            if (isSyntaxMenuVisible) {
                SyntaxActionMenu(
                    onActionSelected = { action ->
                        val newTfv = insertSyntaxAction(action, textFieldValue)
                        textFieldValue = newTfv
                        viewModel.onConsoleInput(newTfv.text)
                        isSyntaxMenuVisible = false
                        focusRequester.requestFocus()
                    },
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = 20.dp, bottom = 4.dp),
                )
            }

            JavaScriptInputPill(
                textFieldValue = textFieldValue,
                onValueChange = { newTfv ->
                    textFieldValue = newTfv
                    viewModel.onConsoleInput(newTfv.text)
                },
                onSubmit = {
                    isSyntaxMenuVisible = false
                    viewModel.onConsoleInput(textFieldValue.text)
                    viewModel.onConsoleSubmit(textFieldValue.text)
                    isExpanded = true
                    focusRequester.requestFocus()
                },
                enabled = consoleState.isReady,
                focusRequester = focusRequester,
                onExpand = { isExpanded = true },
                onToggleSyntaxMenu = {
                    isSyntaxMenuVisible = !isSyntaxMenuVisible
                },
                onInputClick = {
                    isSyntaxMenuVisible = false
                    isExpanded = true
                },
                onFocusChanged = { focused ->
                    isConsoleFocused = focused
                },
            )
        }
    }
}

/**
 * Compact vertical syntax-action strip anchored to the console's left `<>` button.
 * Contains icon-only / symbol-only actions with no text labels, compact width (~44dp),
 * and vertical scrolling bounded to fit within the viewport above the keyboard.
 */
@Composable
fun SyntaxActionMenu(
    onActionSelected: (SyntaxAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(44.dp)
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.browserNavContainer,
        shadowElevation = 6.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onBrowserNavContainer.copy(alpha = 0.15f),
        ),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SyntaxActions.forEach { action ->
                IconButton(
                    onClick = { onActionSelected(action) },
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = action.symbol,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onBrowserNavContainer,
                    )
                }
            }
        }
    }
}

/**
 * Legacy syntax palette containing hard-to-type JavaScript tokens.
 */
@Deprecated("Use SyntaxActionMenu instead.")
@Suppress("DEPRECATION")
@Composable
fun SyntaxShortcutPalette(
    onInsertShortcut: (SyntaxShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.browserNavContainer.copy(alpha = 0.95f),
        tonalElevation = 2.dp,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(SyntaxShortcuts) { shortcut ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.browserNavContainer,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.onBrowserNavContainer.copy(alpha = 0.2f),
                    ),
                    onClick = { onInsertShortcut(shortcut) },
                ) {
                    Text(
                        text = shortcut.displayLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onBrowserNavContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * First-class persistent ChatGPT-style input pill at the bottom of the shell.
 */
@Composable
fun JavaScriptInputPill(
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onExpand: () -> Unit = {},
    onToggleSyntaxPalette: () -> Unit = {},
    onToggleSyntaxMenu: () -> Unit = onToggleSyntaxPalette,
    onInputClick: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onInputClick()
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left JavaScript syntax shortcut / menu button
            IconButton(
                onClick = onToggleSyntaxMenu,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Code,
                    contentDescription = "JavaScript syntax shortcuts",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Monospace input field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onInputClick()
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            onFocusChanged(focusState.isFocused)
                        },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                    ),
                    decorationBox = { innerTextField ->
                        if (textFieldValue.text.isEmpty()) {
                            Text(
                                text = "Type JavaScript…",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    },
                )
            }

            // Right evaluation / submit button
            IconButton(
                onClick = {
                    if (textFieldValue.text.isNotBlank()) {
                        onSubmit()
                    }
                },
                enabled = textFieldValue.text.isNotBlank(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Evaluate JavaScript",
                    tint = if (textFieldValue.text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Expanded console history and status overlay.
 */
@Composable
fun ExpandedConsolePanel(
    consoleState: ConsoleState,
    onHistoryBack: () -> Unit,
    onHistoryForward: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.browserNavContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBrowserNavContainer,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JavaScript Console",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = phaseLabel(consoleState.phase),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // History navigation buttons
                IconButton(
                    onClick = onHistoryBack,
                    enabled = consoleState.isReady,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "History back",
                        tint = if (consoleState.isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }

                IconButton(
                    onClick = onHistoryForward,
                    enabled = consoleState.isReady,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "History forward",
                        tint = if (consoleState.isReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Collapse button
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Collapse console",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Stat chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConsoleStatChip(
                    label = "Entries",
                    value = consoleState.entries.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                ConsoleStatChip(
                    label = "Target",
                    value = consoleState.targetId?.take(8) ?: "—",
                    modifier = Modifier.weight(1f),
                )
                ConsoleStatChip(
                    label = "Status",
                    value = consoleState.phase.name,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Output logs
            val listState = rememberLazyListState()
            LaunchedEffect(consoleState.entries.size) {
                if (consoleState.entries.isNotEmpty()) {
                    listState.animateScrollToItem(consoleState.entries.size - 1)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (consoleState.entries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Filled.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(32.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No console output yet",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Attach a target or evaluate expressions below to stream live runtime logs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
                    items(consoleState.entries, key = { it.id }) { entry ->
                        ConsoleEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun ConsoleStatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ConsoleEntryRow(entry: ConsoleEntry) {
    var isExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var isTruncated by rememberSaveable(entry.id) {
        mutableStateOf(entry.displayText.lines().size > 6)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = isTruncated || isExpanded,
            ) {
                isExpanded = !isExpanded
            },
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (entry.kind == ConsoleEntryKind.COMMAND) {
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.displayText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 6,
                    overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    onTextLayout = { textLayoutResult ->
                        if (!isExpanded && (textLayoutResult.hasVisualOverflow ||
                                (textLayoutResult.lineCount > 0 && textLayoutResult.isLineEllipsized(textLayoutResult.lineCount - 1)))
                        ) {
                            isTruncated = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color = severityColor(entry.severity)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.displayText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 6,
                    overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                    onTextLayout = { textLayoutResult ->
                        if (!isExpanded && (textLayoutResult.hasVisualOverflow ||
                                (textLayoutResult.lineCount > 0 && textLayoutResult.isLineEllipsized(textLayoutResult.lineCount - 1)))
                        ) {
                            isTruncated = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun severityColor(severity: ConsoleSeverity): Color = when (severity) {
    ConsoleSeverity.ERROR -> MaterialTheme.colorScheme.error
    ConsoleSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    ConsoleSeverity.INFO -> MaterialTheme.colorScheme.primary
    ConsoleSeverity.DEBUG -> MaterialTheme.colorScheme.secondary
    ConsoleSeverity.LOG -> MaterialTheme.colorScheme.outline
}

fun phaseLabel(phase: ConsoleLifecyclePhase): String = when (phase) {
    ConsoleLifecyclePhase.READY -> "Ready · Live console attached"
    ConsoleLifecyclePhase.INACTIVE -> "Inactive · No target attached"
    ConsoleLifecyclePhase.ENABLING -> "Enabling console domains…"
    ConsoleLifecyclePhase.UNSUPPORTED -> "Unsupported console domains"
    ConsoleLifecyclePhase.FAILED -> "Console enablement failed"
}

@Deprecated("Use JavaScriptConsoleShell instead. The left drawer has been refactored into the persistent bottom console pill.")
@Composable
fun JavaConsoleDrawerContent(
    viewModel: ShellViewModel,
    state: ShellUiState,
    onOpenConsoleTool: () -> Unit = {},
) {
    JavaScriptConsoleShell(viewModel = viewModel, state = state)
}