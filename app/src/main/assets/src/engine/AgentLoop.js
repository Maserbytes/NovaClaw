/**
 * Native-backed AgentLoop.
 *
 * The JS no longer simulates reasoning. It submits the goal to the Kotlin
 * ReAct loop and renders the events that come back through AndroidBridge.
 * The public API is unchanged so app.js needs no edits.
 */

let activeNativeLoop = null;

class AgentLoop {
  constructor(options = {}) {
    this.modelEngine = options.modelEngine;
    this.callbacks = {
      onStateChange: options.onStateChange || (() => {}),
      onStep: options.onStep || (() => {}),
      onThought: options.onThought || (() => {}),
      onAction: options.onAction || (() => {}),
      onVerification: options.onVerification || (() => {}),
      onSafetyPrompt: options.onSafetyPrompt || (() => {}),
      onComplete: options.onComplete || (() => {}),
      onStopped: options.onStopped || (() => {}),
      onError: options.onError || (() => {}),
    };
    this.state = 'IDLE';
    this.isRunning = false;
    this.stepIndex = 0;
    this.goal = '';
    activeNativeLoop = this;
  }

  async start(goal) {
    if (this.isRunning) return;
    if (!window.AndroidBridge || !window.AndroidBridge.submitTask) {
      this.callbacks.onError('Native bridge unavailable. Open this UI inside the NovaClaw app.');
      return;
    }
    this.goal = goal;
    this.stepIndex = 0;
    this.isRunning = true;
    this._setState('STARTING');
    const modelId = this.modelEngine && this.modelEngine.getNativeModelId
      ? this.modelEngine.getNativeModelId() : 'gemma4-e2b';
    const ok = window.AndroidBridge.submitTask(goal, modelId);
    if (!ok) {
      this.isRunning = false;
      this._setState("IDLE");
    }
  }

  stop() {
    if (!this.isRunning || this.state === 'STOPPING') return;
    this._setState('STOPPING');
    if (window.AndroidBridge && window.AndroidBridge.stopTask) window.AndroidBridge.stopTask();
  }

  resumeAfterSafetyApproval(approvalKey) {
    if (window.AndroidBridge && window.AndroidBridge.resolveSafety) {
      window.AndroidBridge.resolveSafety(approvalKey, true);
    }
    this._setState('ACTING');
  }

  denySafety(approvalKey) {
    if (window.AndroidBridge && window.AndroidBridge.resolveSafety) {
      window.AndroidBridge.resolveSafety(approvalKey, false);
    }
  }

  _setState(s) {
    this.state = s;
    this.callbacks.onStateChange(s);
  }
}

/** Called by MainActivity for every AgentEvent. */
if (typeof window !== 'undefined') {
  window.novaOnEvent = function (json) {
  const loop = activeNativeLoop;
  if (!loop) return;
  let e;
  try { e = typeof json === 'string' ? JSON.parse(json) : json; } catch (err) { return; }

  switch (e.type) {
    case 'state':
      loop.isRunning = e.name !== 'IDLE';
      loop._setState(e.name);
      break;
    case 'step':
      loop.stepIndex = e.index;
      loop.callbacks.onStep({
        stepIndex: e.index,
        stage: 'OBSERVATION',
        appName: e.app,
        generation: e.generation,
      });
      break;
    case 'thought':
      loop.callbacks.onThought({
        stepIndex: e.step,
        thought: e.text,
        plan: { action: 'thinking', payload: e.text },
        backend: e.backend,
      });
      break;
    case 'action':
      loop.callbacks.onAction({
        stepIndex: e.step,
        action: { type: e.tool, label: e.label, targetId: null },
      });
      break;
    case 'verification':
      loop.callbacks.onVerification({
        stepIndex: e.step,
        verification: { success: e.success, message: e.message },
        preHash: e.pre,
        postHash: e.post,
      });
      break;
    case 'safety':
      loop.callbacks.onSafetyPrompt({
        safetyResult: { reason: e.reason, approvalKey: e.key, requiresConfirmation: true },
        action: { type: 'sensitive-action', label: e.target, payload: e.target },
        stepIndex: loop.stepIndex,
      });
      break;
    case 'complete':
      loop.callbacks.onComplete({
        success: true,
        goal: loop.goal,
        totalSteps: e.steps,
        isConversational: e.conversational,
        summary: e.summary,
      });
      break;
    case 'stopped':
      loop.callbacks.onStopped();
      break;
    case 'error':
      loop.callbacks.onError(e.message);
      break;
  }
};
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { AgentLoop };
}
