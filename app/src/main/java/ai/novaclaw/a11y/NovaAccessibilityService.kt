package ai.novaclaw.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Accessibility service with verified dispatch.
 *
 * Differences from PokeClaw:
 *  - gesture callbacks run on a dedicated HandlerThread, never the main thread
 *  - timeouts are derived from the stroke duration, so no fixed 5 s blind wait
 *  - observations are immutable, generation-stamped snapshots (no mutable global node map)
 *  - every action reports before/after fingerprints; "no change" is an explicit failure
 */
class NovaAccessibilityService : AccessibilityService() {

    private lateinit var callbackThread: HandlerThread
    private var callbackHandler: Handler? = null
    private val generation = AtomicLong(0)

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    companion object {
        const val MAX_NODES = 90
        const val MAX_DEPTH = 14

        @Volatile
        var instance: NovaAccessibilityService? = null
            private set

        private val readyLatch = CountDownLatch(1)

        /** Non-blocking readiness check. Never poll with a 20 s sleep. */
        fun isReady(): Boolean = readyLatch.count == 0L && instance != null

        /** Wait at most 3 s for the service to bind. */
        fun awaitReady(timeoutMs: Long = 3_000L): Boolean =
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun isEnabled(context: android.content.Context): Boolean {
            val expected = "${context.packageName}/${NovaAccessibilityService::class.java.name}"
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return raw.split(':').any {
                it.equals(expected, ignoreCase = true) ||
                    it.endsWith(".${NovaAccessibilityService::class.java.simpleName}", ignoreCase = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        callbackThread = HandlerThread("nova-gesture-cb").apply { start() }
        callbackHandler = Handler(callbackThread.looper)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        readyLatch.countDown()
        Toast.makeText(this, "NovaClaw accessibility connected", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        instance = null
        callbackThread.quitSafely()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    private fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow
        ?: windows.orEmpty().firstOrNull { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isActive || window.isFocused)
        }?.root

    /** Retry transient null roots while Android is switching windows. */
    suspend fun captureScreen(goal: String = ""): ScreenSnapshot? = withContext(Dispatchers.Default) {
        repeat(10) { attempt ->
            val root = activeRoot()
            if (root != null) {
                try {
                    return@withContext buildSnapshot(root, goal)
                } finally {
                    // Root node is owned by the framework; do not recycle it.
                }
            }
            if (attempt < 9) delay(180)
        }
        null
    }

    /** True when a real system dialog/alert window is present (not just a null root). */
    fun hasBlockingDialog(): Boolean {
        return windows?.any { w -> w.type == AccessibilityWindowInfo.TYPE_SYSTEM } ?: false
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo, goal: String): ScreenSnapshot {
        val nodes = mutableListOf<UiNode>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH || !node.isVisibleToUser || node.isPassword) continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() < 8 || bounds.height() < 8) continue

            val label = (node.text?.toString() ?: node.contentDescription?.toString()
                ?: node.viewIdResourceName?.substringAfterLast('/') ?: "").trim()
            val interactive = node.isClickable || node.isEditable || node.isScrollable

            if ((interactive || label.isNotEmpty()) && label.isNotBlank()) {
                val key = "${label}|${bounds.left / 20}|${bounds.top / 20}"
                if (seen.add(key)) {
                    val id = "n${nodes.size + 1}"
                    nodes.add(
                        UiNode(
                            id = id,
                            label = label,
                            className = node.className?.toString().orEmpty(),
                            clickable = node.isClickable,
                            editable = node.isEditable,
                            scrollable = node.isScrollable,
                            bounds = bounds,
                            centerX = bounds.centerX(),
                            centerY = bounds.centerY(),
                        )
                    )
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        }

        val pkg = root.packageName?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }

        val fingerprintSource = buildString {
            append(pkg).append('|')
            nodes.forEach { append(it.label).append(':').append(it.bounds.left / 20)
                .append(',').append(it.bounds.top / 20).append(';') }
        }
        val snapshot = ScreenSnapshot(
            generation = generation.incrementAndGet(),
            packageName = pkg,
            appName = label,
            nodes = nodes,
            fingerprint = shortHash(fingerprintSource),
        )
        lastSnapshot = snapshot
        return snapshot
    }

    private fun shortHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ------------------------------------------------------------------
    // Verified dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatch a gesture with a callback on a background HandlerThread and a
     * timeout derived from the gesture duration. Never blocks the main thread.
     */
    private fun dispatchVerified(gesture: GestureDescription, timeoutMs: Long): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Gestures must never be dispatched on the main thread"
        }
        val handler = callbackHandler ?: return false
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, handler)
        if (!dispatched) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && completed.get()
    }

    private fun settleAndFingerprint(pre: String): Pair<Boolean, String> {
        val budget = 1_400L
        var waited = 0L
        while (waited < budget) {
            Thread.sleep(100)
            waited += 100
            val root = activeRoot() ?: continue
            val nodes = mutableListOf<String>()
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
                val (n, d) = queue.removeFirst()
                if (d > MAX_DEPTH || !n.isVisibleToUser) continue
                val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
                if (t.isNotEmpty()) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    nodes.add("$t:${r.left / 20},${r.top / 20}")
                }
                if (d < MAX_DEPTH) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to d + 1) }
            }
            val fp = shortHash(nodes.sorted().joinToString(";"))
            if (fp != pre) return true to fp
        }
        return false to pre
    }

    /** Reject actions bound to a stale observation. */
    private fun assertFresh(observedGeneration: Long?): String? {
        if (observedGeneration == null) return "No observed generation supplied"
        val current = generation.get()
        if (current != observedGeneration ||
            activeRoot()?.packageName?.toString() != lastSnapshot?.packageName) {
            return "The active screen changed. Re-observe before acting."
        }
        return null
    }

    suspend fun tapAt(x: Int, y: Int, durationMs: Long = 80L): ActionOutcome = withContext(Dispatchers.Default) {
        val pre = currentFingerprint()
        val started = System.currentTimeMillis()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchVerified(gesture, durationMs + 2_000L)
        val (changed, post) = if (dispatched) settleAndFingerprint(pre) else false to pre
        ActionOutcome(
            success = dispatched && changed,
            reason = when {
                !dispatched -> "Gesture was not delivered (accessibility callback timeout)"
                !changed -> "Tap dispatched but the screen did not change — likely missed target or ANR"
                else -> "Tap verified: screen changed"
            },
            preFingerprint = pre,
            postFingerprint = post,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    suspend fun tapNode(id: String, observedGeneration: Long?): ActionOutcome = withContext(Dispatchers.Default) {
        assertFresh(observedGeneration)?.let {
            return@withContext ActionOutcome(false, it, "", "", 0L)
        }
        val node = lastSnapshot?.node(id)
            ?: return@withContext ActionOutcome(false, "Unknown node $id in current snapshot", "", "", 0L)
        val root = activeRoot()
            ?: return@withContext ActionOutcome(false, "No active window", "", "", 0L)
        val target = findClickable(root, node)
            ?: return@withContext ActionOutcome(false, "The observed control moved or disappeared", "", "", 0L)
        val started = System.currentTimeMillis()
        val pre = currentFingerprint()
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val (changed, post) = if (accepted) settleAndFingerprint(pre) else false to pre
        val focused = accepted && target.refresh() && target.isEditable && target.isFocused
        ActionOutcome(accepted && (changed || focused), when {
            focused -> "Input field focused"
            changed -> "Screen changed after tap"
            else -> "Android did not confirm the tap"
        }, pre, post, System.currentTimeMillis() - started)
    }

    private fun findClickable(root: AccessibilityNodeInfo, node: UiNode): AccessibilityNodeInfo? {
        if (!root.isVisibleToUser) return null
        val label = (root.text ?: root.contentDescription ?: root.viewIdResourceName?.substringAfterLast('/'))
            ?.toString()?.trim().orEmpty()
        if (root.isClickable && root.isEnabled && label == node.label &&
            Rect().also(root::getBoundsInScreen) == node.bounds) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { findClickable(it, node) }?.let { return it }
        }
        return null
    }

    suspend fun scroll(direction: String, observedGeneration: Long?): ActionOutcome = withContext(Dispatchers.Default) {
        assertFresh(observedGeneration)?.let {
            return@withContext ActionOutcome(false, it, "", "", 0L)
        }
        val pre = currentFingerprint()
        val started = System.currentTimeMillis()

        // Prefer the native scroll action on a scrollable container.
        val root = activeRoot()
        val scrolled = root?.let { scrollNative(it, direction.equals("down", true)) } ?: false
        if (scrolled) {
            val (changed, post) = settleAndFingerprint(pre)
            return@withContext ActionOutcome(
                changed, if (changed) "Native scroll verified" else "Scroll action accepted but screen did not change",
                pre, post, System.currentTimeMillis() - started,
            )
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val down = !direction.equals("up", ignoreCase = true)
        val path = Path().apply {
            if (down) {
                moveTo(w * 0.5f, h * 0.78f); lineTo(w * 0.5f, h * 0.28f)
            } else {
                moveTo(w * 0.5f, h * 0.28f); lineTo(w * 0.5f, h * 0.78f)
            }
        }
        val duration = 450L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val dispatched = dispatchVerified(gesture, duration + 2_000L)
        val (changed, post) = if (dispatched) settleAndFingerprint(pre) else false to pre
        ActionOutcome(
            dispatched && changed,
            when {
                !dispatched -> "Scroll gesture not delivered"
                !changed -> "Scroll dispatched but screen did not change"
                else -> "Scroll verified"
            },
            pre, post, System.currentTimeMillis() - started,
        )
    }

    private fun scrollNative(node: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollNative(child, forward)) return true
        }
        return false
    }

    /** Set text with read-back; "action accepted" is not treated as success. */
    suspend fun setText(id: String, observedGeneration: Long?, text: String): ActionOutcome =
        withContext(Dispatchers.Default) {
            assertFresh(observedGeneration)?.let {
                return@withContext ActionOutcome(false, it, "", "", 0L)
            }
            val nodeInfo = lastSnapshot?.node(id)
                ?: return@withContext ActionOutcome(false, "Unknown node $id", "", "", 0L)
            val pre = currentFingerprint()
            val started = System.currentTimeMillis()
            val root = activeRoot()
                ?: return@withContext ActionOutcome(false, "No active window", pre, pre, 0L)
            val target = findNodeAt(root, nodeInfo.bounds)
            if (target == null) {
                return@withContext ActionOutcome(false, "Editable field not found", pre, pre, 0L)
            }
            var accepted = false
            run { // labeled so a verified read-back breaks the retry loop
                repeat(3) { attempt ->
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    val fresh = findNodeAt(activeRoot() ?: root, nodeInfo.bounds)
                    if (accepted && fresh?.text?.toString() == text) return@run
                    Thread.sleep(150 + attempt * 100L)
                }
            }
            val post = if (accepted) settleAndFingerprint(pre).second else pre
            val verified = accepted && target.refresh() && target.text?.toString() == text
            ActionOutcome(
                verified,
                if (verified) "Text set and verified" else "Text action accepted but read-back failed",
                pre, post, System.currentTimeMillis() - started,
            )
        }

    private fun findNodeAt(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        if (!root.isVisibleToUser) return null
        val r = Rect().also { root.getBoundsInScreen(it) }
        if (r == bounds && root.isEditable && !root.isPassword) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { findNodeAt(it, bounds)?.let { found -> return it } }
        }
        return null
    }

    suspend fun typeIntoFocused(text: String, generation: Long?): ActionOutcome = withContext(Dispatchers.Default) {
        assertFresh(generation)?.let { return@withContext ActionOutcome(false, it, "", "", 0L) }
        val pre = currentFingerprint()
        val started = System.currentTimeMillis()
        val target = activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable && !it.isPassword }
            ?: return@withContext ActionOutcome(false, "Focus a visible text field first", pre, pre, 0L)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val post = if (accepted) settleAndFingerprint(pre).second else pre
        val verified = accepted && target.refresh() && target.text?.toString() == text
        ActionOutcome(verified,
            if (verified) "Text entered" else "Android did not confirm the entered text",
            pre, post, System.currentTimeMillis() - started)
    }

    suspend fun back(): ActionOutcome = globalAction(GLOBAL_ACTION_BACK, "back")
    suspend fun home(): ActionOutcome = globalAction(GLOBAL_ACTION_HOME, "home")

    private suspend fun globalAction(action: Int, name: String): ActionOutcome =
        withContext(Dispatchers.Default) {
            val pre = currentFingerprint()
            val started = System.currentTimeMillis()
            val ok = performGlobalAction(action)
            val (changed, post) = if (ok) settleAndFingerprint(pre) else false to pre
            ActionOutcome(ok && changed, if (ok && changed) "$name verified" else "$name failed or no change",
                pre, post, System.currentTimeMillis() - started)
        }

    suspend fun submitFocusedField(generation: Long?): ActionOutcome = withContext(Dispatchers.Default) {
        assertFresh(generation)?.let { return@withContext ActionOutcome(false, it, "", "", 0L) }
        val pre = currentFingerprint()
        val start = System.currentTimeMillis()
        val target = activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val accepted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            target?.isEditable == true && !target.isPassword &&
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        val (changed, post) = if (accepted) settleAndFingerprint(pre) else false to pre
        ActionOutcome(changed,
            if (changed) "Input submitted" else "Use a visible submit or search control",
            pre, post, System.currentTimeMillis() - start)
    }

    private fun currentFingerprint(): String {
        val root = activeRoot() ?: return ""
        val parts = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty() && parts.size < MAX_NODES) {
            val (n, d) = queue.removeFirst()
            if (d > MAX_DEPTH || !n.isVisibleToUser) continue
            val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
            if (t.isNotEmpty()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                parts.add("$t:${r.left / 20},${r.top / 20}")
            }
            if (d < MAX_DEPTH) for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it to d + 1) }
        }
        return shortHash(parts.sorted().joinToString(";"))
    }

    suspend fun openApp(packageName: String): ActionOutcome = withContext(Dispatchers.Default) {
        val pre = currentFingerprint()
        val started = System.currentTimeMillis()
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext ActionOutcome(false, "No launchable app matches $packageName", pre, pre, 0L)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        repeat(25) {
            delay(120)
            if (activeRoot()?.packageName?.toString() == packageName) {
                val label = intent.component?.let { packageManager.getActivityInfo(it, 0).loadLabel(packageManager) }
                    ?: packageName
                return@withContext ActionOutcome(true, "Opened $label", pre, currentFingerprint(),
                    System.currentTimeMillis() - started)
            }
        }
        ActionOutcome(false, "Android did not show $packageName in the foreground", pre, currentFingerprint(),
            System.currentTimeMillis() - started)
    }

    fun resolvePackage(name: String): String? {
        val query = name.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return null
        val apps = packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        apps.firstOrNull { it.packageName.equals(query, true) }?.let { return it.packageName }
        val labeled = apps.map { it.packageName to packageManager.getApplicationLabel(it).toString() }
        val exact = labeled.filter { (_, label) -> label.equals(query, true) }
        if (exact.isNotEmpty()) return exact.singleOrNull()?.first
        return labeled.filter { (_, label) -> label.contains(query, true) }.singleOrNull()?.first
    }


}
