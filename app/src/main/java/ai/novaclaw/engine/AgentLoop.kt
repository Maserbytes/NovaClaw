package ai.novaclaw.engine

import ai.novaclaw.a11y.NovaAccessibilityService
import ai.novaclaw.a11y.ScreenSnapshot
import ai.novaclaw.model.LiteRTEngine
import ai.novaclaw.model.ModelCatalog
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
        var session: LiteRTEngine.Session? = null
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

            if (model.kind != ModelInfo.Kind.LITE_RT) {
                emit(AgentEvent.Error("Cloud models are disabled in this build. Select Gemma 4 on-device."))
                return
            }
            if (!ModelCatalog.isInstalled(context, model)) {
                emit(AgentEvent.Error("${model.name} is not installed. Download it from the Model Hub."))
                return
            }
            if (!ModelCatalog.verifyIntegrity(context, model)) {
                emit(AgentEvent.Error("Model file failed its integrity check. Delete and re-download it."))
                return
            }

            val backend = engine.open(ModelCatalog.path(context, model), preferGpu = false)
            NovaLog.i(tag, "engine ready backend=$backend")

            val playbook = PlaybookManager.match(goal)
            val systemPrompt = PromptComposer.compose(context, playbook)
            val selectedTools = ToolSelector.select(goal, tools.specs)
            NovaLog.i(tag, "prompt composed playbook=${playbook?.id ?: "none"} tools=${selectedTools.map { it.name }}")

            session = engine.openSession(systemPrompt, selectedTools)
            emit(AgentEvent.State("THINKING"))

            val budget = TaskBudget()
            val stuck = StuckDetector()
            val recentHistory = history.recent(3)

            for (step in 1..30) {
                if (!isRunning || !scope.isActive) return

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
                    if (recentHistory.isNotEmpty()) {
                        append("\n\nRECENT CONVERSATION:\n")
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

                val reply = session!!.send(prompt)
                if (reply.hasDroppedToolCall) {
                    NovaLog.w(tag, "Malformed tool call detected; rebuilding conversation")
                    session!!.markDirty()
                    emit(AgentEvent.Error("The model emitted a malformed tool call. Retrying with a fresh session."))
                    session!!.close()
                    session = engine.openSession(systemPrompt, selectedTools)
                    delay(200)
                    continue
                }
                val thought = reply.thought ?: reply.text ?: "Choosing the next action…"
                emit(AgentEvent.Thought(step, thought, backend))
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
                val verification = result.verification
                emit(AgentEvent.Verification(
                    step, result.success, result.message,
                    verification?.preFingerprint.orEmpty(), verification?.postFingerprint.orEmpty(),
                ))

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
