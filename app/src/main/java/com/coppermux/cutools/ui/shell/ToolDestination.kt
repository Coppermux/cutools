package com.coppermux.cutools.ui.shell

import android.content.Context
import android.content.Intent
import com.coppermux.cutools.ui.ElementsActivity
import com.coppermux.cutools.ui.ConsoleActivity
import com.coppermux.cutools.ui.network.NetworkActivity
import com.coppermux.cutools.ui.profiling.PerformanceToolScreen
import com.coppermux.cutools.ui.securitystorage.SecurityStorageToolScreen
import com.coppermux.cutools.ui.audits.AuditActivity
import com.coppermux.cutools.ui.performance.PerformanceActivity
import com.coppermux.cutools.ui.memory.MemoryActivity
import com.coppermux.cutools.ui.target.TargetActivity
import com.coppermux.cutools.ui.security.SecurityActivity
import com.coppermux.cutools.ui.accessibility.AccessibilityActivity
import com.coppermux.cutools.ui.emulation.EmulationActivity

/**
 * Phase 17 — DevTool destination registry.
 *
 * Every destination maps to a concrete, manifest-registered CuTools tool
 * activity. A destination is the only way the shell/home workspace launches a
 * tool; no tool launch is ever synthesized (each entry is backed by a real
 * registered activity).
 */
enum class ToolDestination(
    val label: String,
    val description: String,
    val activityClass: Class<*>,
) {
    TARGETS(
        "Targets",
        "Discover, select, and attach to CDP page targets.",
        TargetActivity::class.java,
    ),
    ELEMENTS(
        "Elements",
        "Inspect the live DOM of the attached page.",
        ElementsActivity::class.java,
    ),
    CONSOLE(
        "Console",
        "Stream JavaScript console messages and evaluate expressions.",
        ConsoleActivity::class.java,
    ),
    NETWORK(
        "Network",
        "Monitor genuine HTTP/CDP network activity.",
        NetworkActivity::class.java,
    ),
    PERFORMANCE(
        "Performance",
        "Tracing, CPU, and Heap profiling dashboard.",
        PerformanceToolScreen::class.java,
    ),
    SECURITY_STORAGE(
        "Security & Storage",
        "Security, storage quota, IndexedDB, and cache inspection.",
        SecurityStorageToolScreen::class.java,
    ),
    AUDITS(
        "Audits",
        "Run accessibility and performance audits.",
        AuditActivity::class.java,
    ),
    PERF_METRICS(
        "Perf Metrics",
        "Real-time performance metric sampling.",
        PerformanceActivity::class.java,
    ),
    MEMORY(
        "Memory",
        "Heap and memory profiling charts.",
        MemoryActivity::class.java,
    ),
    SECURITY(
        "Security Inspect",
        "Certificate and transport security details.",
        SecurityActivity::class.java,
    ),
    ACCESSIBILITY(
        "Accessibility",
        "Accessibility tree and node capabilities.",
        AccessibilityActivity::class.java,
    ),
    EMULATION(
        "Emulation",
        "Device metrics, network, and media emulation.",
        EmulationActivity::class.java,
    ),
}

/** Launches a registered [ToolDestination] from the shell UI. */
fun launchTool(context: Context, destination: ToolDestination) {
    context.startActivity(Intent(context, destination.activityClass))
}