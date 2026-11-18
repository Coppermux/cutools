# CuTools — Native CDP/WebView Diagnostics for Android

CuTools is an open-source Android developer tool providing real-time diagnostics for embedded Chromium WebView through the Chrome DevTools Protocol (CDP).

---

## Overview

CuTools brings desktop-class web diagnostic and inspection tools directly to mobile devices. By embedding an Android Chromium WebView alongside a local, in-process Chrome DevTools Protocol (CDP) bridge, CuTools provides mobile developers, QA engineers, and security researchers with real-time runtime visibility, network inspection, and live JavaScript execution—all directly on the target Android device without requiring an external desktop tether or remote debugging setup.

### Developer Tool vs. General-Purpose Browser

> [!IMPORTANT]
> **CuTools is not a general-purpose web browser.**
> 
> CuTools is designed exclusively as a developer and diagnostics utility. While it hosts an embedded Chromium WebView to render target web applications under test, it deliberately excludes consumer browser features such as multi-tab navigation, bookmark synchronization, autofill, password managers, browser extensions, and consumer history. The embedded browser viewport exists solely to serve as a live inspection target for genuine DevTools diagnostics.

---

## Architecture & Native CDP Bridge

CuTools operates entirely on-device, coupling the Android presentation layer to the embedded Chromium WebView runtime through an internal loopback transport.

```
┌─────────────────────────────────────────────────────────────────┐
│                    CuTools Android Shell                        │
│   • Embedded WebView Viewport (Screen 0)                        │
│   • DevTools Diagnostic Dashboard (Screen 1)                    │
│   • Persistent JavaScript Console Drawer (REPL)                 │
│   • Floating Draggable F12 Navigation Button                    │
└────────────────────────────────┬────────────────────────────────┘
                                 │ StateFlow & ViewModel events
┌────────────────────────────────▼────────────────────────────────┐
│                   Diagnostic Kernel & Engines                   │
│   • DiagnosticKernel (Lifecycle, Session Identity & Epochs)     │
│   • ConsoleInspector & ExecutionEngine (Runtime & Console)      │
│   • NetworkEngine (Network & Fetch event processing)            │
│   • DomInspector & CssInspector (DOM & CSS style analysis)      │
│   • Emulation, Storage, Security & Profiling Engines            │
└────────────────────────────────┬────────────────────────────────┘
                                 │ JSON-RPC (CommandDispatcher & Router)
┌────────────────────────────────▼────────────────────────────────┐
│               AndroidCdpBridge (In-Process Bridge)               │
│   • Binds to local ephemeral TCP port (127.0.0.1:0)             │
│   • Cryptographic SEC-01 in-memory token authentication         │
│   • Forwards securely to Chromium abstract domain socket        │
│     (@webview_devtools_remote_<PID>)                            │
└────────────────────────────────┬────────────────────────────────┘
                                 │ Abstract UNIX domain socket
┌────────────────────────────────▼────────────────────────────────┐
│             Android System WebView (Chromium Core)              │
└─────────────────────────────────────────────────────────────────┘
```

### Architectural Guarantees

- **Native In-Process Bridge (`AndroidCdpBridge`):** Connects the internal WebSocket client stack (`WebSocketCdpTransport`) directly to the Android Chromium WebView's abstract UNIX domain socket (`@webview_devtools_remote_<PID>`).
- **Dynamic Ephemeral Binding:** Binds loopback TCP to `127.0.0.1:0`, ensuring zero port collisions with external debuggers or ADB port forward tunnels.
- **SEC-01 Security Enclave:** Every internal connection is verified against an in-memory, cryptographically random authentication token. Credentials are automatically sanitized before forwarding to Chromium's native protocol handlers.
- **No-Fake-Capability Contract:** Every diagnostic value—DOM nodes, CSS styles, console entries, network requests, and runtime evaluations—is retrieved from genuine CDP commands and event streams against the active target. No metrics, headers, or logs are synthetic or simulated.
- **Target Lifecycle & Synchronization:** The `DiagnosticKernel` coordinates session epochs, target discovery, and cleanup. Diagnostics automatically bind when a page target is attached and clear cleanly on detachment or navigation to prevent stale state.

---

## Functioning V1.0 Capabilities

### 1. Genuine CDP-Backed Diagnostics
- Automatic discovery of and attachment to the embedded WebView page target.
- Real-time command dispatching and event routing across active CDP domains.
- Session epoch tracking via `DiagnosticKernel` ensuring clean target isolation.

### 2. Interactive JavaScript Console
- **Persistent REPL Input Pill:** Anchored bottom console pill accessible across both viewport and dashboard views.
- **Direct Expression Evaluation:** Evaluates arbitrary JavaScript expressions directly against the attached page context via `Runtime.evaluate`, returning formatted outputs, object structures, and error traces.
- **Live Console Event Stream:** Streams `console.log`, `console.info`, `console.warn`, `console.error`, and uncaught exceptions in real time with precise timestamps.
- **Command History:** Retains previously executed commands for rapid recall and editing.
- **Severity Filtering:** Filter console output by log level (All, Errors, Warnings, Info).

### 3. Real-Time Network Inspection
- **Live HTTP/HTTPS Capture:** Monitors all outgoing requests and incoming responses initiated by the WebView using the CDP `Network` domain.
- **Request & Response Inspection:** View request URLs, HTTP methods, status codes, headers, and MIME types.
- **Resource Timing:** Detailed timing breakdowns for DNS, connection handshake, SSL negotiation, TTFB, and payload download.
- **Response Body Decoding:** Inspect decoded response payloads directly within the app.
- **cURL Command Export:** Generate copy-ready `curl` command strings matching the exact headers, method, and payload of any captured request.
- **Live Search & Feed Filtering:** Filter requests on the fly by URL query strings and status.

### 4. Workspaces (Session Management)
- **Room Persistence:** Saved sessions are stored locally in SQLite via Android Jetpack Room (`CuToolsDatabase`).
- **Dual Categorization:** Categorize inspection sessions into `WORK` (high-priority development tasks) or `HOME` (general/personal testing).
- **Workspaces Grid:** Visual grid showing saved targets, generated titles, URLs, and relative timestamps.
- **Session Actions:** Save active sessions, rename sessions, pin high-priority targets, and restore sessions with a single tap.
- **Intelligent Name Generator:** Automatically derives human-readable workspace titles from target hostnames and paths.

### 5. Dual-Surface Shell & Navigation
- **Dual-Screen Layout:** Swipe smoothly between the embedded WebView viewport (Screen 0) and the DevTools Diagnostic Dashboard (Screen 1).
- **Floating F12 Button:** A draggable concentric navigation button allowing instant one-tap switching between the viewport and diagnostics. Button position persists across launches (`F12ButtonPreferences`) and can be toggled via the menu.
- **Top Bar Controls:** Address bar with instant HTTP/HTTPS protocol indicators, Go navigation, and a top-right menu.
- **Application Reset (`ResetCoordinator`):** Centralized reset action that gracefully cleans up active subscriptions, disconnects the bridge, and purges transient diagnostic state across all subsystems.
- **Theme Support:** Native Dark and Light themes with dynamic contrast adjustment.

### 6. Additional Implemented Diagnostics
- **DOM & CSS Inspection:** Explore live DOM trees and inspect computed and matched CSS rules tied to selected DOM nodes (`DOM` and `CSS` domains).
- **Device Emulation:** Test mobile web layouts by overriding device dimensions, scale factors, user-agent strings, and touch emulation (`Emulation` domain).
- **Storage & Cache Introspection:** Query origin storage quotas, LocalStorage, SessionStorage, IndexedDB databases, and CacheStorage caches (`Storage`, `IndexedDB`, and `CacheStorage` domains).
- **Performance & Profiling:** Inspect timeline tracing events, collect CPU execution profiles, and capture heap snapshots (`Tracing`, `Profiler`, and `HeapProfiler` domains).
- **Accessibility Inspection:** Examine the accessibility tree with role and property mappings (`Accessibility` domain).
- **Security Diagnostics:** Inspect SSL/TLS certificate chains, security states, and certificate error alerts (`Security` domain).

---

## Scope & Explicit Exclusions

> [!WARNING]
> **Features Excluded from V1.0:**
> - **Fault Injection:** Fault injection (such as simulated network latency, synthetic HTTP status codes, connection drops, and request body mocking) is experimental and explicitly **excluded from V1.0**. These capabilities are deferred to future roadmap releases.
> - **General Browsing:** Features such as multi-tab browsing, cloud synchronization, download managers, extensions, and autofill are out of scope.
> - **Remote Desktop Chrome Debugging:** CuTools V1.0 is engineered specifically for local, in-process embedded Android WebView diagnostics and does not target external desktop Chrome instances over a network.

---

## System Requirements

| Component | Minimum Requirement | Recommended |
| :--- | :--- | :--- |
| **Android OS** | Android 8.0 (API Level 26 / Oreo) | Android 14+ (API Level 34+) |
| **Compile / Target SDK** | Target SDK 35 (`compileSdk 35`) | Android 15 (SDK 35) |
| **Android System WebView** | Modern Chromium-based WebView | Latest stable Google Play release |
| **Java Development Kit (JDK)** | JDK 17 | JDK 17 (e.g. OpenJDK 17) |
| **Build Tooling** | Gradle 8.11.1 (via included wrapper) | Android Studio Ladybug / CLI |

---

## Building from Source

### 1. Clone the Repository

```bash
git clone https://github.com/coppermux/cutools.git
cd cutools
```

### 2. Build Debug APK

```bash
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Run Unit Tests

Execute the deterministic unit test suite:

```bash
./gradlew test
```

### 4. Run Lint Checks

Run Android Lint static analysis:

```bash
./gradlew lintDebug
```

### 5. Build Release APK

```bash
./gradlew assembleRelease
```
The output APK is generated at:
`app/build/outputs/apk/release/app-release.apk`

> [!NOTE]
> **Release Signing:** If `keystore.properties` is present at the repository root, `assembleRelease` signs the APK using the specified keystore. If the file is absent, an unsigned release artifact is produced.
> 
> Sample `keystore.properties`:
> ```properties
> storeFile=/path/to/release.jks
> storePassword=your_keystore_password
> keyAlias=cutools
> keyPassword=your_key_password
> ```

---

## Installation & Getting Started

### Install via ADB

Ensure your Android device or emulator is connected with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Quick Start Guide

1. **Launch CuTools:** Open the application on your Android device or emulator.
2. **Navigate:** Enter the target web URL in the top address bar and tap **Go** (or select a saved workspace tile).
3. **Automatic Target Binding:** CuTools starts the in-process CDP bridge, connects to the WebView's abstract UNIX socket, and attaches to the active page session.
4. **Inspect Diagnostics:**
   - Swipe left or tap the **Floating F12 Button** to open the **DevTools Diagnostic Dashboard**.
   - Monitor real-time network traffic, status codes, and console messages.
   - Use the search bar or filter chips to isolate specific requests or errors.
5. **Interactive Console:**
   - Tap the bottom **Console pill** to expand the interactive REPL.
   - Type JavaScript expressions and submit to evaluate directly in the live WebView context.
6. **Save Workspace:**
   - Open the top-right hamburger menu and select **Save Session** to bookmark your active inspection context into `Work` or `Home` workspaces for future sessions.

---

## Known Limitations

- **Single Active Page Target:** CuTools attaches diagnostics to one active embedded WebView page target at a time.
- **Underlying WebView Dependency:** Diagnostic capabilities and protocol fidelity reflect the version and features of the Chromium-based Android System WebView installed on the device.
- **Experimental Features Excluded:** Fault injection and mock response rules are not supported in V1.0.

---

## License

CuTools is licensed under the **GNU General Public License version 3** (`GPL-3.0-only`).

See the [LICENSE](file:///home/coppermux/Projects/cutools/LICENSE) file in the root directory for the full license text.
