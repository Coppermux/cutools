package com.coppermux.cutools.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coppermux.cutools.core.browser.CurlGenerator
import com.coppermux.cutools.core.browser.DashboardEvent
import com.coppermux.cutools.ui.theme.concentricMiddle
import com.coppermux.cutools.ui.theme.concentricOuter
import com.coppermux.cutools.ui.theme.onSuccessContainer
import com.coppermux.cutools.ui.theme.success
import com.coppermux.cutools.ui.theme.successContainer
import com.coppermux.cutools.core.browser.DashboardFilter
import com.coppermux.cutools.core.browser.DashboardSeverity
import com.coppermux.cutools.core.browser.DashboardStreamState
import com.coppermux.cutools.core.browser.FaultInterceptorEngine
import com.coppermux.cutools.core.browser.FaultRule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phase 18/19 — DevTools Dashboard: the live chronological interceptor feed.
 *
 * Screen 1 of the dual-axis shell. Renders every event captured by the
 * embedded WebView interceptor engine ([com.coppermux.cutools.core.browser.WebViewEngine])
 * in strict chronological order as severity-based color-coded cards
 * (Green / Yellow / Red). The [DashboardFilterBar] drives the chip filter and
 * the live search; the feed auto-scrolls to the newest event so it reads like
 * a live stream.
 *
 * Phase 19 — interactive cards. Tapping a card expands it into full header and
 * payload inspection (request/response headers, raw bodies with copy actions)
 * plus an action row carrying `Copy as cURL` and the `Inject Fault` dropdown
 * (latency slider, HTTP status override, connection-drop toggle, mock body
 * editor). Cards with an active fault rule render a one-tap-removable
 * `[⚡ Fault Active]` badge derived from the live [FaultInterceptorEngine]
 * rules mirrored through [activeFaultRules].
 */
@Composable
fun DevToolsDashboard(
    dashboardState: DashboardStreamState,
    activeFaultRules: List<FaultRule>,
    currentPageUrl: String? = null,
    isRecording: Boolean = true,
    isNetworkDropped: Boolean = false,
    onFilterSelected: (DashboardFilter) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearFeed: () -> Unit,
    onApplyFaultRule: (FaultRule) -> Unit = {},
    onRemoveFaultRule: (String) -> Unit = {},
    onToggleDropConnection: (Boolean) -> Unit = {},
    toggleRecording: () -> Unit = {},
    onShareTrace: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        FeedHeader(
            totalCount = dashboardState.eventCount,
            visibleCount = dashboardState.visibleCount,
            isFiltered = dashboardState.isFiltered,
        )
        DashboardFilterBar(
            selectedFilter = dashboardState.filter,
            searchQuery = dashboardState.searchQuery,
            isRecording = isRecording,
            onFilterSelected = onFilterSelected,
            onSearchQueryChanged = onSearchQueryChanged,
            onClearFeed = onClearFeed,
            toggleRecording = toggleRecording,
        )
        HorizontalDivider()

        when {
            dashboardState.events.isEmpty() -> {
                FeedEmptyState(
                    title = "No interceptor events yet",
                    detail = "Browse in the browser view and network + console traffic streams here in chronological order.",
                )
            }
            dashboardState.visibleEvents.isEmpty() -> {
                FeedEmptyState(
                    title = "No events match the active filter",
                    detail = "Switch chips or clear the search to show the full chronological feed.",
                )
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(dashboardState.visibleEvents, key = { it.id }) { event ->
                        DashboardEventCard(
                            event = event,
                            activeFaultRules = activeFaultRules,
                            currentPageUrl = currentPageUrl,
                            onApplyFaultRule = onApplyFaultRule,
                            onRemoveFaultRule = onRemoveFaultRule,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // Keep the live stream pinned to the newest (chronologically last) event.
                LaunchedEffect(dashboardState.visibleEvents.size) {
                    if (dashboardState.visibleEvents.isNotEmpty()) {
                        listState.animateScrollToItem(dashboardState.visibleEvents.lastIndex)
                    }
                }
            }
        }
    }
}

// ================================================================
// Feed building blocks
// ================================================================

// ================================================================
// Feed building blocks
// ================================================================

@Composable
private fun FeedHeader(
    totalCount: Int,
    visibleCount: Int,
    isFiltered: Boolean,
) {
    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "DevTools Feed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when {
                    isFiltered -> "$visibleCount of $totalCount events visible"
                    else -> "$totalCount events · real-time interceptor"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ConcentricCircleInfoGraphic(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.concentricOuter),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.concentricMiddle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FeedEmptyState(
    title: String,
    detail: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ConcentricCircleInfoGraphic()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ================================================================
// Severity color coding
// ================================================================

private data class SeverityPalette(
    val container: Color,
    val content: Color,
    val border: Color,
)

/** Green / Yellow / Red card tinting derived from genuine event severity. */
@Composable
private fun DashboardSeverity.palette(): SeverityPalette = when (this) {
    DashboardSeverity.SUCCESS -> SeverityPalette(
        container = MaterialTheme.colorScheme.successContainer,
        content = MaterialTheme.colorScheme.onSuccessContainer,
        border = MaterialTheme.colorScheme.success,
    )
    DashboardSeverity.WARNING -> SeverityPalette(
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        border = MaterialTheme.colorScheme.tertiary,
    )
    DashboardSeverity.ERROR -> SeverityPalette(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        border = MaterialTheme.colorScheme.error,
    )
}

// ================================================================
// Expandable event card
// ================================================================

@Composable
private fun DashboardEventCard(
    event: DashboardEvent,
    activeFaultRules: List<FaultRule>,
    currentPageUrl: String? = null,
    onApplyFaultRule: (FaultRule) -> Unit,
    onRemoveFaultRule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = event.severity.palette()
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.border),
        colors = CardDefaults.cardColors(containerColor = palette.container),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Tappable header region toggles the full inspection details.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                // Header: type/status badge + timestamp + duration.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EventBadge(
                        text = event.kind,
                        background = palette.content,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.content,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formatTimestamp(event.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.content,
                    )
                    event.durationMs?.let { duration ->
                        Text(
                            text = " · ${formatDuration(duration)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = palette.content,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Summary: URL for network events, message text for console.
                Text(
                    text = eventSummary(event),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = palette.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val meta = eventMeta(event)
                if (meta != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.content.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Render "Fault Applied" badge ONLY if this request was genuinely fault-injected.
            // Historical requests that completed normally do NOT display armed-rule badges.
            if (event.isFaultApplied) {
                Spacer(modifier = Modifier.height(8.dp))
                FaultAppliedBadge(event = event)
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = palette.content.copy(alpha = 0.25f))
                Spacer(modifier = Modifier.height(8.dp))
                val activeRule = activeFaultRules.firstOrNull {
                    FaultInterceptorEngine.matches(it, event.url ?: "", event.method ?: "GET")
                }
                EventDetails(
                    event = event,
                    palette = palette,
                    activeRule = activeRule,
                    currentPageUrl = currentPageUrl,
                    onApplyFaultRule = onApplyFaultRule,
                    onRemoveFaultRule = onRemoveFaultRule,
                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                )
            }
        }
    }
}

// ================================================================
// Expanded details & inspection
// ================================================================

@Composable
private fun EventDetails(
    event: DashboardEvent,
    palette: SeverityPalette,
    activeRule: FaultRule? = null,
    currentPageUrl: String? = null,
    onApplyFaultRule: (FaultRule) -> Unit = {},
    onRemoveFaultRule: (String) -> Unit = {},
    onCopy: (String) -> Unit,
) {
    if (!event.isNetwork) {
        ConsoleDetails(event = event, palette = palette, onCopy = onCopy)
        return
    }

    if (activeRule != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "⚡ Armed for endpoint: ${activeRule.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TextButton(
                    onClick = { onRemoveFaultRule(activeRule.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    SectionHeader(title = "Request Headers", color = palette.content)
    KeyValueList(entries = event.requestHeaders, color = palette.content)

    if (event.requestBody != null) {
        BodySection(
            title = "Request Body",
            body = event.requestBody,
            color = palette.content,
            onCopy = { onCopy(event.requestBody) },
        )
    }

    SectionHeader(title = "Response Headers", color = palette.content)
    KeyValueList(entries = event.responseHeaders, color = palette.content)

    if (event.responseBody != null) {
        BodySection(
            title = "Response Body",
            body = event.responseBody,
            color = palette.content,
            onCopy = { onCopy(event.responseBody) },
        )
    }

    if (event.url != null) {
        ActionRow(
            event = event,
            palette = palette,
            onCopy = { onCopy(CurlGenerator.fromEvent(event)) },
        )
    }
}

@Composable
private fun ConsoleDetails(
    event: DashboardEvent,
    palette: SeverityPalette,
    onCopy: (String) -> Unit,
) {
    BodySection(
        title = "Message",
        body = event.message ?: "(empty)",
        color = palette.content,
        onCopy = { onCopy(event.message ?: "") },
    )
    event.url?.let { source ->
        SectionHeader(title = "Source", color = palette.content)
        Text(
            text = source,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = palette.content.copy(alpha = 0.85f),
        )
    }
    event.detail?.let { line ->
        SectionHeader(title = "Detail", color = palette.content)
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = palette.content.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun KeyValueList(
    entries: Map<String, String>,
    color: Color,
) {
    if (entries.isEmpty()) {
        Text(
            text = "— none captured —",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        return
    }
    entries.forEach { (name, value) ->
        Text(
            text = "$name: $value",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color.copy(alpha = 0.9f),
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }
}

@Composable
private fun BodySection(
    title: String,
    body: String,
    color: Color,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCopy) {
            Text("Copy")
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

// ================================================================
// Action row + fault injection dropdown
// ================================================================

@Composable
private fun ActionRow(
    event: DashboardEvent,
    palette: SeverityPalette,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        OutlinedButton(
            onClick = onCopy,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, palette.border),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = palette.content,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "cURL",
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Copy as cURL",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * V2.0 Backlog: Advanced Fault Injection interface implementation preserved for V2.0.
 * In V1.0, fault injection is superseded by the single kernel-synchronized Drop Connection toggle.
 */
@Composable
private fun FaultInjectionPanel(
    event: DashboardEvent,
    palette: SeverityPalette,
    currentPageUrl: String? = null,
    onApplyFaultRule: (FaultRule) -> Unit,
) {
    val defaultUrl = currentPageUrl?.trim()?.takeIf { it.isNotEmpty() } ?: event.url ?: ""
    var urlPattern by remember(event.id, defaultUrl) { mutableStateOf(defaultUrl) }
    var selectedMethod by remember(event.id) { mutableStateOf<String?>(null) }
    var latencyMs by remember(event.id) { mutableStateOf(0f) }
    var statusCode by remember(event.id) { mutableStateOf<Int?>(null) }
    var dropConnection by remember(event.id) { mutableStateOf(false) }
    var mockBody by remember(event.id) { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = palette.container.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Target URL pattern
            OutlinedTextField(
                value = urlPattern,
                onValueChange = { urlPattern = it },
                label = { Text("Target URL pattern (exact or glob)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                singleLine = true,
            )

            // Quick URL selector between current page URL and event URL
            if (!currentPageUrl.isNullOrBlank() && !event.url.isNullOrBlank() && currentPageUrl != event.url) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = urlPattern == currentPageUrl,
                        onClick = { urlPattern = currentPageUrl },
                        label = { Text("Page URL") },
                    )
                    FilterChip(
                        selected = urlPattern == event.url,
                        onClick = { urlPattern = event.url ?: "" },
                        label = { Text("Event URL") },
                    )
                }
            }

            // HTTP Method selection (ALL by default)
            Text(
                text = "HTTP Method",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = palette.content,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(null, "GET", "POST", "PUT", "DELETE").forEach { m ->
                    FilterChip(
                        selected = selectedMethod == m,
                        onClick = { selectedMethod = m },
                        label = { Text(m ?: "ALL") },
                    )
                }
            }

            // Latency / delay slider with haptic ticks (500 ms steps).
            Text(
                text = "Latency / Delay",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = palette.content,
            )
            Slider(
                value = latencyMs,
                onValueChange = {
                    latencyMs = it
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                valueRange = 0f..10_000f,
                steps = 19,
            )
            Text(
                text = if (latencyMs <= 0f) "No delay" else "+${latencyMs.roundToInt()} ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = palette.content.copy(alpha = 0.85f),
            )

            // HTTP status override chips.
            Text(
                text = "HTTP Status Override",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (dropConnection) palette.content.copy(alpha = 0.4f) else palette.content,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(400, 401, 403, 404, 500, 503).forEach { code ->
                    FilterChip(
                        selected = statusCode == code,
                        enabled = !dropConnection,
                        onClick = {
                            if (!dropConnection) {
                                statusCode = if (statusCode == code) null else code
                            }
                        },
                        label = { Text(code.toString()) },
                    )
                }
            }

            // Drop connection toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Drop Connection",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.content,
                    )
                    Text(
                        text = "Simulates socket failure (reported as net::ERR_FAILED / network error in WebView)",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.content.copy(alpha = 0.8f),
                    )
                }
                Switch(
                    checked = dropConnection,
                    onCheckedChange = { checked ->
                        dropConnection = checked
                        if (checked) {
                            statusCode = null
                            mockBody = ""
                        }
                    },
                )
            }

            // Response body mock editor.
            OutlinedTextField(
                value = mockBody,
                onValueChange = { mockBody = it },
                label = { Text(if (dropConnection) "Mock JSON body (disabled for drop)" else "Mock JSON body (optional)") },
                enabled = !dropConnection,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                minLines = 2,
                maxLines = 5,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        val pattern = urlPattern.trim().ifBlank { defaultUrl.ifBlank { return@Button } }
                        onApplyFaultRule(
                            FaultRule(
                                id = "fault:$pattern:${selectedMethod ?: "ALL"}",
                                urlPattern = pattern,
                                method = selectedMethod,
                                latencyMs = latencyMs.roundToInt().coerceAtLeast(0),
                                statusCode = if (!dropConnection) statusCode else null,
                                dropConnection = dropConnection,
                                mockBody = if (!dropConnection) mockBody.trim().ifBlank { null } else null,
                            )
                        )
                    },
                ) {
                    Text("Apply Fault")
                }
            }
        }
    }
}

@Composable
private fun ArmedFaultRulesBar(
    rules: List<FaultRule>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                text = "⚡ Armed Fault Rules (${rules.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(rules, key = { it.id }) { rule ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val patternDisplay = if (rule.urlPattern.length > 25) {
                                "..." + rule.urlPattern.takeLast(22)
                            } else {
                                rule.urlPattern
                            }
                            Text(
                                text = "${rule.method ?: "*"} $patternDisplay · ${rule.summary}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = { onRemove(rule.id) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove rule",
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaultAppliedBadge(
    event: DashboardEvent,
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⚡ Fault Applied",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            val label = when {
                event.kind == "CONNECTION DROP" -> "drop"
                event.statusCode != null && event.statusCode >= 400 -> "HTTP ${event.statusCode}"
                else -> event.detail ?: ""
            }
            if (label.isNotBlank()) {
                Text(
                    text = " · $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EventBadge(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = background,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.surface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The card title line: console text for console events, URL for network. */
private fun eventSummary(event: DashboardEvent): String =
    event.message?.takeIf { it.isNotBlank() } ?: event.url ?: event.kind

/** Secondary monospace meta line (method + status), or null when absent. */
private fun eventMeta(event: DashboardEvent): String? {
    val methods = mutableListOf<String>()
    event.method?.let { methods.add(it) }
    if (event.statusCode != null) {
        methods.add(
            "${event.statusCode}${event.statusText?.let { " $it" } ?: ""}".trim(),
        )
    }
    event.detail?.let { methods.add(it) }
    return methods.joinToString("  ").ifBlank { null }
}

// ================================================================
// Formatting helpers
// ================================================================

private val TIME_FORMATTER = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

/** Formats an epoch-millis WebView interception time for display. */
private fun formatTimestamp(millis: Long): String =
    synchronized(TIME_FORMATTER) { TIME_FORMATTER.format(Date(millis)) }

/** Formats a measured response duration, e.g. "124 ms". */
private fun formatDuration(durationMs: Long): String =
    if (durationMs >= 1000) {
        String.format(Locale.getDefault(), "%.2f s", durationMs / 1000.0)
    } else {
        "$durationMs ms"
    }