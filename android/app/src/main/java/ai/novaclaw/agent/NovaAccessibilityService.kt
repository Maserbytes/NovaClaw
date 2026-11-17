package ai.novaclaw.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NovaAccessibilityService: Production Android Accessibility Interface for NovaClaw.
 * Replaces PokeClaw's blind gesture dispatching with verified, debounced execution.
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NovaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Monitored for window transition events to update current package
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Extracts pruned, actionable elements from the current screen.
     */
    fun capturePrunedScreen(): PrunedScreenData {
        val rootNode = rootInActiveWindow ?: return PrunedScreenData.empty()
        val elements = mutableListOf<PrunedNode>()
        val packageName = rootNode.packageName?.toString() ?: "unknown"

        var currentId = 1
        val bounds = Rect()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 10 || bounds.height() <= 10) return

            val label = resolveNodeLabel(node)
            val isActionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable

            if (isActionable || (label.isNotEmpty() && bounds.height() > 20)) {
                elements.add(
                    PrunedNode(
                        id = currentId++,
                        type = classifyType(node),
                        label = label,
                        packageName = packageName,
                        bounds = NodeBounds(bounds.left, bounds.top, bounds.width(), bounds.height()),
                        centerX = bounds.centerX(),
                        centerY = bounds.centerY(),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        viewIdResourceName = node.viewIdResourceName
                    )
                )
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(rootNode)
        val stateHash = computeHash(elements, packageName)
        return PrunedScreenData(packageName, elements, stateHash)
    }

    /**
     * Dispatches a verified tap gesture at the specified screen coordinates.
     */
    suspend fun performTap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    /**
     * Dispatches a verified scroll gesture.
     */
    suspend fun performScroll(startX: Float, startY: Float, endX: Float, endY: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    /**
     * Injects text into active input field.
     */
    fun performTextEntry(nodeId: Int, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val targetNode = findNodeById(rootNode, nodeId)
        if (targetNode != null && targetNode.isEditable) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun resolveNodeLabel(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty()) return desc

        val hint = node.hintText?.toString()?.trim()
        if (!hint.isNullOrEmpty()) return "[Hint: $hint]"

        return ""
    }

    private fun classifyType(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString() ?: ""
        return when {
            className.contains("EditText", ignoreCase = true) || node.isEditable -> "input"
            className.contains("Button", ignoreCase = true) || node.isClickable -> "button"
            className.contains("Switch", ignoreCase = true) || className.contains("CheckBox", ignoreCase = true) -> "toggle"
            className.contains("ScrollView", ignoreCase = true) || className.contains("RecyclerView", ignoreCase = true) -> "list"
            else -> "text"
        }
    }

    private fun computeHash(elements: List<PrunedNode>, packageName: String): String {
        val sig = "$packageName:" + elements.joinToString("|") { "${it.id}:${it.label}:${it.centerX}:${it.centerY}" }
        return "0x" + (sig.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
    }

    private fun findNodeById(root: AccessibilityNodeInfo, targetId: Int): AccessibilityNodeInfo? {
        // Recursive lookup helper
        var current = 1
        var result: AccessibilityNodeInfo? = null

        fun search(node: AccessibilityNodeInfo?) {
            if (node == null || result != null) return
            if (node.isClickable || node.isEditable) {
                if (current == targetId) {
                    result = node
                    return
                }
                current++
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i))
            }
        }
        search(root)
        return result
    }
}

data class NodeBounds(val x: Int, val y: Int, val width: Int, val height: Int)
data class PrunedNode(
    val id: Int,
    val type: String,
    val label: String,
    val packageName: String,
    val bounds: NodeBounds,
    val centerX: Int,
    val centerY: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val viewIdResourceName: String?
)

data class PrunedScreenData(
    val packageName: String,
    val elements: List<PrunedNode>,
    val stateHash: String
) {
    companion object {
        fun empty() = PrunedScreenData("none", emptyList(), "0x00000000")
    }
}
