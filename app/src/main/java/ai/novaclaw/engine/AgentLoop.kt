package ai.novaclaw.engine

import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.a11y.ScreenSnapshot
import ai.novaclaw.model.CloudConfig
import ai.novaclaw.model.CloudEngine
import ai.novaclaw.model.LiteRTEngine
import ai.novaclaw.model.ModelCatalog
import ai.novaclaw.model.ReasoningSession
import ai.novaclaw.model.ModelInfo
import ai.novaclaw.safety.SafetyShield
import ai.novaclaw.state.ConversationStore
import ai.novaclaw.state.TaskSessionStore
import ai.novaclaw.util.NovaLog
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class AgentEvent(val type: String) {
    data class State(val name: String) : AgentEvent("state")
    data class Step(val index: Int, val generation: Long, val app: String) : AgentEvent("step")
    data class Thought(val step: Int, val text: String, val backend: String) : AgentEvent("thought")
    data class Action(val step: Int, val tool: String, val label: String) : AgentEvent("action")
    data class Verification(
        val step: Int,
        val success: Boolean,
        val message: String,
        val pre: String,
        val post: String,
    ) : AgentEvent("verification")
    data class Budget(val step: Int, val usedPercent: Int, val near: Boolean) : AgentEvent("budget")
    data class SafetyPrompt(val key: String, val reason: String, val target: String) : AgentEvent("safety")
    data class Complete(val summary: String, val conversational: Boolean, val steps: Int) : AgentEvent("complete")
    data class Error(val message: String) : AgentEvent("error")
}

class AgentLoop(
    private val context: Context,
    private val engine: LiteRTEngine,
    private val cloudEngine: CloudEngine,
    private val accessibility: NovaAccessibilityService,
    private val tools: ToolRegistry,
    private val safety: SafetyShield,
    val sessionStore: TaskSessionStore,
    private val history: ConversationStore,
    private val emit: (AgentEvent) -> Unit,
) {
    private val tag = "AgentLoop"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            NovaLog.e(tag, "Unhandled agent error", t)
            emit(AgentEvent.Error(t.message ?: "Agent crashed"))
            emit(AgentEvent.State("IDLE"))
        }
    )

    @Volatile
    var isRunning = false
        private set

    private var job: Job? = null
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var session: ai.novaclaw.model.ReasoningSession? = null

    fun start(goal: String, model: ModelInfo) {
        if (isRunning) return
        NovaLog.i(tag, "start: goal='${goal.take(120)}' model=${model.id}")
        isRunning = true
        safety.reset()
        job = scope.launch { run(goal, model) }
    }

    fun stop() {
        NovaLog.i(tag, "stop requested")
        sessionStore.markStopping()
        isRunning = false
        pendingApproval?.complete(false)
        job?.cancel()
        job = null
        emit(AgentEvent.State("IDLE"))
    }

    fun resolveApproval(key: String, approved: Boolean) {
        NovaLog.i(tag, "safety approval key=$key approved=$approved")
        if (approved) safety.approve(key) else safety.deny(key)
        pendingApproval?.complete(approved)
    }

    private suspend fun run(goal: String, model: ModelInfo) {
        if (!sessionStore.tryAcquire("task_${System.currentTimeMillis()}", goal)) {
            NovaLog.w(tag, "Rejected overlapping task")
            emit(AgentEvent.Error("Another task is already running. Stop it first."))
            isRunning = false
            return
        }
        session = null
        try {
            history.append("user", goal)

            if (PipelineRouter.route(goal) == PipelineRouter.Route.DeviceInfo) {
                NovaLog.decision(tag, "route", "device_info", "no action words in goal")
                emit(AgentEvent.State("ACTING"))
                emit(AgentEvent.Step(1, 0L, "device"))
                emit(AgentEvent.Thought(1, "Deterministic device query — no model inference needed", "deterministic"))
                emit(AgentEvent.Action(1, "get_device_info", "local"))
                val info = DeviceInfoTool.read(context)
                emit(AgentEvent.Verification(1, true, "Read directly from Android APIs", "", ""))
                history.append("nova", info)
                emit(AgentEvent.Complete(info, true, 1))
                return
            }

            if (ConversationRouter.isChatOnly(goal)) {
                NovaLog.decision(tag, "route", "general_chat", "no phone action requested — answer with the LLM")
                emit(AgentEvent.State("THINKING"))
                val cloud = model.kind == ModelInfo.Kind.CLOUD
                val key = if (cloud) CloudConfig.apiKey(context) else ""
                if (cloud && key.isBlank()) {
                    emit(AgentEvent.Error("Add a Gemini API key in Model Hub → Cloud / API to chat."))
                    return
                }
                if (!cloud && !ModelCatalog.isInstalled(context, model)) {
                    emit(AgentEvent.Error("Download a model in Model Hub → On-Device Models to chat."))
                    return
                }
                val backendLabel = if (cloud) "Gemini Flash (cloud)" else
                    engine.open(ModelCatalog.path(context, model), preferGpu = false)
                // Chat mode keeps only non-visual tools: it can answer normally,
                // but can also send a message / open an app if the user asks.
                val chatTools = tools.specs.filter {
                    it.name in setOf("finish", "send_message", "open_app", "get_device_info")
                }
                val chatSystem = "You are NovaClaw, a capable general-purpose AI assistant. " +
                    "Answer the user directly: explain, reason, write, calculate or chat naturally. " +
                    "You DO have phone tools: send_message(to,text), open_app(app_name), get_device_info. " +
                    "If the user asks you to actually send a message or open an app, call that tool instead of saying you cannot. " +
                    "For normal questions, answer in plain text. " +
                    "Never narrate the question or your reasoning."
                val chatSession = if (cloud) {
                    openCloudWithFallback(key, chatSystem, chatTools)
                } else {
                    engine.openSession(chatSystem, chatTools)
                }
                session = chatSession
                val context = history.recent(3)
                val chatPrompt = buildString {
                    if (context.isNotEmpty()) {
                        append("Recent conversation:\n")
                        context.forEach { append("- ").append(it).append('\n') }
                        append('\n')
                    }
                    append("User: ").append(goal)
                }
                var reply = sendWithCloudFallback(chatPrompt, chatSystem, chatTools, cloud)
                if (looksLikeMeta(reply.text)) {
                    NovaLog.w(tag, "Model emitted meta-reasoning; retrying with strict answer prompt")
                    reply = sendWithCloudFallback(
                        "Give ONLY the final answer to: $goal\n" +
                            "Do not narrate the question, your reasoning, or tool calls.",
                        chatSystem, noTools, cloud
                    )
                }
                val fromTool = reply.toolCalls.firstOrNull()?.arguments?.get("summary")?.toString()
                val answer = (reply.text ?: fromTool ?: "Hello! How can I help?").trim()
                emit(AgentEvent.Thought(1, thoughtFor(reply, "Reasoning and answering — no phone action needed"), backendLabel))
                history.append("nova", answer)
                emit(AgentEvent.Complete(answer, true, 1))
                return
            }

            emit(AgentEvent.State("OBSERVING"))
            if (!NovaAccessibilityService.awaitReady() || NovaAccessibilityService.instance == null) {
                val msg = "Accessibility service disconnected. Re-enable NovaClaw in Settings → Accessibility " +
                    "(on Xiaomi/HyperOS also allow Autostart and disable battery restriction)."
                NovaLog.w(tag, msg)
                emit(AgentEvent.Error(msg))
                return
            }
            val shortcut = TaskShortcuts.match(goal)
            if (shortcut != null) {
                NovaLog.decision(tag, "route", "shortcut:${shortcut.tool}", "unambiguous one-step intent")
                emit(AgentEvent.State("ACTING"))
                emit(AgentEvent.Step(1, 0L, "device"))
                emit(AgentEvent.Action(1, shortcut.tool, shortcut.args.values.first().toString()))
                val r = tools.execute(shortcut.tool, shortcut.args, null)
                emit(AgentEvent.Verification(1, r.success, r.message, "", ""))
                history.append("nova", r.message)
                emit(AgentEvent.Complete(r.message, false, 1))
                return
            }

            val isCloud = model.kind == ModelInfo.Kind.CLOUD
            val cloudKey = if (isCloud) CloudConfig.apiKey(context) else ""
            if (isCloud && cloudKey.isBlank()) {
                emit(AgentEvent.Error("Add a Gemini API key in Model Hub → Cloud / API, then try again."))
                return
            }
            if (!isCloud && !ModelCatalog.isInstalled(context, model)) {
                emit(AgentEvent.Error("${model.name} is not installed. Download it from Model Hub → On-Device Models."))
                return
            }
            if (!isCloud && !ModelCatalog.verifyIntegrity(context, model)) {
                emit(AgentEvent.Error("Model file failed its integrity check. Delete and re-download it."))
                return
            }

            val backend = if (isCloud) "Gemini Flash (cloud)" else
                engine.open(ModelCatalog.path(context, model), preferGpu = false)
            NovaLog.i(tag, "engine ready backend=$backend")

            val playbook = PlaybookManager.match(goal)
            val systemPrompt = PromptComposer.compose(context, playbook)
            val selectedTools = ToolSelector.select(goal, tools.specs)
            NovaLog.i(tag, "prompt composed playbook=${playbook?.id ?: "none"} tools=${selectedTools.map { it.name }}")

            session = if (isCloud) {
                openCloudWithFallback(cloudKey, systemPrompt, selectedTools)
            } else {
                engine.openSession(systemPrompt, selectedTools)
            }
            emit(AgentEvent.State("THINKING"))

            val budget = TaskBudget()
            val stuck = StuckDetector()
            val recentHistory = history.recent(3)
            val actionHistory = mutableListOf<String>()
            var consecutiveFailures = 0

            for (step in 1..30) {
                if (!isRunning || !scope.isActive) return

                // LiteRT has a hard KV-cache limit; cloud keeps its own history.
                val local = session as? LiteRTEngine.Session
                if (!isCloud && step > 1 && (step % 3 == 1 || local?.dirty == true)) {
                    NovaLog.i(tag, "Refreshing local session at step $step to stay within KV bounds")
                    local?.close()
                    session = engine.openSession(systemPrompt, selectedTools)
                }

                val snapshot: ScreenSnapshot = accessibility.captureScreen(goal)
                    ?: run {
                        val msg = if (accessibility.hasBlockingDialog())
                            "A system dialog is blocking automation. Dismiss it and retry."
                        else "Could not read the screen. Wait for the transition and retry."
                        NovaLog.w(tag, msg)
                        emit(AgentEvent.Error(msg))
                        return
                    }
                emit(AgentEvent.Step(step, snapshot.generation, snapshot.appName))

                val prompt = buildString {
                    append(snapshot.compactPrompt(goal))
                    if (actionHistory.isNotEmpty()) {
                        append("\n\nRECENT ACTIONS IN THIS TASK:\n")
                        actionHistory.takeLast(3).forEach { append("- ").append(it).append('\n') }
                    }
                    if (recentHistory.isNotEmpty()) {
                        append("\n\nCONVERSATION CONTEXT:\n")
                        recentHistory.forEach { append("- ").append(it).append('\n') }
                    }
                }

                val status = budget.record(prompt)
                emit(AgentEvent.Budget(step, status.used, status.near))
                if (status.exhausted) {
                    NovaLog.w(tag, "Budget exhausted at step $step")
                    emit(AgentEvent.Error("Budget exhausted. Stopping to avoid runaway work."))
                    return
                }

                val reply = sendWithCloudFallback(prompt, systemPrompt, selectedTools, isCloud)
                if (reply.hasDroppedToolCall) {
                    NovaLog.w(tag, "Malformed tool call detected; rebuilding conversation")
                    session!!.markDirty()
                    emit(AgentEvent.Error("The model emitted a malformed tool call. Retrying with a fresh session."))
                    session!!.close()
                    session = if (isCloud) {
                        openCloudWithFallback(cloudKey, systemPrompt, selectedTools)
                    } else {
                        engine.openSession(systemPrompt, selectedTools)
                    }
                    delay(200)
                    continue
                }
                val thought = reply.thought ?: reply.text ?: "Choosing the next action…"
                emit(AgentEvent.Thought(step, thoughtFor(reply, thought), backend))
                NovaLog.d(tag, "step=$step thought='${thought.take(120)}' toolCalls=${reply.toolCalls.size}")

                val call = reply.toolCalls.firstOrNull()
                if (call == null) {
                    val answer = reply.text?.trim()
                    if (!answer.isNullOrBlank()) {
                        history.append("nova", answer)
                        emit(AgentEvent.Complete(answer, true, step))
                    } else {
                        emit(AgentEvent.Error("No action and no answer. Try rephrasing the task."))
                    }
                    return
                }

                if (call.name == "finish") {
                    val summary = call.arguments["summary"]?.toString() ?: "Task finished"
                    history.append("nova", summary)
                    emit(AgentEvent.Complete(summary, false, step))
                    return
                }

                val verdict = safety.evaluate(call.name, call.arguments, snapshot)
                if (verdict.requiresConfirmation) {
                    emit(AgentEvent.State("SAFETY_CHECK"))
                    val deferred = CompletableDeferred<Boolean>()
                    pendingApproval = deferred
                    emit(AgentEvent.SafetyPrompt(verdict.approvalKey!!, verdict.reason, describe(call, snapshot)))
                    val approved = deferred.await()
                    pendingApproval = null
                    if (!approved || !safety.isApproved(verdict.approvalKey)) {
                        emit(AgentEvent.Error("Action blocked by user"))
                        return
                    }
                } else if (!verdict.allowed) {
                    NovaLog.w(tag, "Blocked ${call.name}: ${verdict.reason}")
                    emit(AgentEvent.Error(verdict.reason))
                    return
                }

                emit(AgentEvent.State("ACTING"))
                emit(AgentEvent.Action(step, call.name, describe(call, snapshot)))
                NovaLog.d(tag, "tool:${call.name}(${call.arguments})")

                val result = tools.execute(call.name, call.arguments, snapshot)
                actionHistory.add("Step $step: ${call.name}(${describe(call, snapshot)}) -> ${result.message.take(40)}")
                val verification = result.verification
                emit(AgentEvent.Verification(
                    step, result.success, result.message,
                    verification?.preFingerprint.orEmpty(), verification?.postFingerprint.orEmpty(),
                ))

                if (!result.success) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        NovaLog.w(tag, "Two consecutive tool failures: ${result.message}")
                        emit(AgentEvent.Error("Stopped: two actions failed in a row — ${result.message}"))
                        return
                    }
                } else {
                    consecutiveFailures = 0
                }

                val signal = stuck.record(call.name, verification?.postFingerprint ?: "")
                if (signal != null) {
                    NovaLog.w(tag, "Stuck signal: ${signal.reason}")
                    emit(AgentEvent.Error("Stopped: ${signal.reason}"))
                    return
                }

                val followUp = session!!.sendToolResponses(
                    listOf(call.name to resultJson(call.name, result)))
                if (followUp.hasDroppedToolCall) session!!.markDirty()
                if (followUp.toolCalls.isEmpty() && !followUp.text.isNullOrBlank()) {
                    val answer = followUp.text.trim()
                    history.append("nova", answer)
                    emit(AgentEvent.Complete(answer, true, step))
                    return
                }

                delay(200)
            }
            emit(AgentEvent.Error("Stopped after 30 steps without completing the task."))
        } catch (t: Throwable) {
            NovaLog.e(tag, "Agent failed", t)
            if (isRunning) emit(AgentEvent.Error(t.message ?: "Agent failed"))
        } finally {
            session?.close()
            sessionStore.release()
            isRunning = false
            emit(AgentEvent.State("IDLE"))
        }
    }

    private fun openCloudWithFallback(
        key: String,
        system: String,
        tools: List<ToolSpec>,
    ): ai.novaclaw.model.ReasoningSession {
        var id = CloudConfig.model(context)
        NovaLog.i(tag, "cloud model=$id")
        return try {
            cloudEngine.openSession(key, id, system, tools)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            // Model retired → try the fallback model name once.
            if (msg.contains("MODEL_UNAVAILABLE", true)) {
                NovaLog.w(tag, "Cloud model $id retired; trying ${CloudConfig.FALLBACK_MODEL}")
                id = CloudConfig.FALLBACK_MODEL
                CloudConfig.setModel(context, id)
                try {
                    return cloudEngine.openSession(key, id, system, tools)
                } catch (t2: Throwable) {
                    return useLocalFallback(t2, system, tools)
                }
            }
            useLocalFallback(t, system, tools)
        }
    }

    /** If the cloud key is invalid or the network times out, answer with local Gemma instead. */
    private fun useLocalFallback(
        cause: Throwable,
        system: String,
        tools: List<ToolSpec>,
    ): ai.novaclaw.model.ReasoningSession {
        val msg = cause.message.orEmpty()
        val recoverable = msg.contains("API_KEY_INVALID", true) ||
            msg.contains("API key not valid", true) ||
            msg.contains("timeout", true) ||
            msg.contains("HTTP 5", true)
        if (!recoverable) throw cause

        val local = ModelCatalog.recommended(context)
        if (!ModelCatalog.isInstalled(context, local)) {
            throw RuntimeException(
                "Cloud failed (${msg.take(80)}) and no local model is installed. " +
                    "Add a valid Gemini key or download Gemma 4 in Model Hub."
            )
        }
        NovaLog.w(tag, "Cloud failed; switching to local ${local.id}: ${msg.take(100)}")
        emit(AgentEvent.Thought(0, "Cloud unavailable — switching to on-device Gemma 4", "fallback"))
        engine.open(ModelCatalog.path(context, local), preferGpu = false)
        return engine.openSession(system, tools)
    }

    private suspend fun sendWithCloudFallback(
        prompt: String,
        system: String,
        tools: List<ToolSpec>,
        isCloud: Boolean,
    ): ai.novaclaw.model.ModelReply {
        return try {
            session!!.send(prompt)
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            val recoverable = isCloud && (
                msg.contains("API_KEY_INVALID", true) ||
                    msg.contains("API key not valid", true) ||
                    msg.contains("RATE_LIMITED", true) ||
                    msg.contains("MODEL_OVERLOADED", true) ||
                    msg.contains("quota", true) ||
                    msg.contains("high demand", true) ||
                    msg.contains("overloaded", true) ||
                    msg.contains("timeout", true) ||
                    msg.contains("HTTP 5", true)
                )
            if (!recoverable) throw t

            // Overload / rate limit: try the alternate Gemini model once first.
            val overloaded = msg.contains("MODEL_OVERLOADED", true) ||
                msg.contains("RATE_LIMITED", true) ||
                msg.contains("high demand", true) ||
                msg.contains("overloaded", true) ||
                msg.contains("HTTP 503", true)
            if (overloaded) {
                val current = CloudConfig.model(context)
                val alternate = if (current == CloudConfig.FALLBACK_MODEL)
                    CloudConfig.PREFERRED_MODEL else CloudConfig.FALLBACK_MODEL
                NovaLog.w(tag, "Cloud overloaded on $current; retrying with $alternate")
                emit(AgentEvent.Thought(0, "Gemini is busy — trying another model", "fallback"))
                CloudConfig.setModel(context, alternate)
                session?.close()
                session = cloudEngine.openSession(
                    CloudConfig.apiKey(context), alternate, system, tools)
                try {
                    return session!!.send(prompt)
                } catch (again: Throwable) {
                    NovaLog.w(tag, "Alternate model also failed: ${again.message?.take(100)}")
                }
            }

            val local = ModelCatalog.recommended(context)
            if (!ModelCatalog.isInstalled(context, local)) {
                throw RuntimeException(
                    if (overloaded)
                        "Gemini is at capacity right now (HTTP 503 high demand). " +
                            "Try again in a moment, or download Gemma 4 in Model Hub for offline answers."
                    else
                        "Cloud failed (${msg.take(80)}) and no local model is installed. " +
                            "Add a valid Gemini key or download Gemma 4 in Model Hub."
                )
            }
            NovaLog.w(tag, "Cloud inference failed, falling back to local: ${msg.take(100)}")
            emit(AgentEvent.Thought(0, "Cloud unavailable — using on-device Gemma 4", "fallback"))
            session?.close()
            engine.open(ModelCatalog.path(context, local), preferGpu = false)
            session = engine.openSession(system, tools)
            session!!.send(prompt)
        }
    }

    private fun thoughtFor(reply: ai.novaclaw.model.ModelReply, fallback: String): String {
        val show = context.getSharedPreferences("nova_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("show_thinking", false)
        if (!show) return fallback
        val reasoning = reply.thought?.trim()
        return if (reasoning.isNullOrBlank()) fallback else "$fallback\n\nModel reasoning:\n${reasoning.take(600)}"
    }

    private fun looksLikeMeta(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val t = text.trim()
        return t.length < 300 && Regex(
            "(?i)^(the user (asked|wants|is asking|said)|here is (the|a) (answer|result)|" +
                "let me (think|reason)|i (need to|will) (answer|calculate|explain))"
        ).containsMatchIn(t)
    }

    private fun describe(call: ai.novaclaw.model.ToolCall, snapshot: ScreenSnapshot?): String {
        val raw = call.arguments.values.firstOrNull()?.toString().orEmpty()
        val id = call.arguments["node_id"]?.toString()
        return if (id != null) "${snapshot?.node(id)?.label?.take(40) ?: id} [$id]" else raw.take(80)
    }

    private fun resultJson(tool: String, result: ToolResult): String {
        val escaped = result.message.replace("\\", "\\\\").replace("\"", "\\\"").take(200)
        return "{\"ok\":${result.success},\"message\":\"$escaped\"}"
    }
}
