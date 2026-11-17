/**
 * NovaClaw Agent Loop (Codex / Antigravity Autonomous Engine)
 * 
 * Orchestrates the full closed-loop ReAct execution cycle:
 * [Observe] -> [Prune (SoM)] -> [Reason (Gemma 4)] -> [Safety Filter] -> 
 * [Execute Gesture] -> [Settle Debounce] -> [Verify Diff] -> [Reflect & Adapt]
 */

class AgentLoop {
  constructor(options = {}) {
    this.pruner = options.pruner;
    this.safetyShield = options.safetyShield;
    this.verifier = options.verifier;
    this.modelEngine = options.modelEngine;
    this.environment = options.environment; // Device or simulator interface

    this.state = 'IDLE'; // IDLE | OBSERVING | THINKING | SAFETY_CHECK | ACTING | VERIFYING | COMPLETED | PAUSED
    this.currentGoal = '';
    this.stepIndex = 0;
    this.maxSteps = options.maxSteps || 15;
    this.history = [];
    this.isRunning = false;

    // Event listeners
    this.callbacks = {
      onStateChange: options.onStateChange || (() => {}),
      onStep: options.onStep || (() => {}),
      onThought: options.onThought || (() => {}),
      onAction: options.onAction || (() => {}),
      onVerification: options.onVerification || (() => {}),
      onSafetyPrompt: options.onSafetyPrompt || (() => {}),
      onComplete: options.onComplete || (() => {}),
      onError: options.onError || (() => {})
    };
  }

  async start(goal) {
    if (this.isRunning) return;

    this.currentGoal = goal;
    this.stepIndex = 0;
    this.history = [];
    this.isRunning = true;
    this.safetyShield.reset();

    this._setState('OBSERVING');
    await this.runLoop();
  }

  stop() {
    this.isRunning = false;
    this.safetyShield.triggerKillSwitch();
    this._setState('IDLE');
  }

  resumeAfterSafetyApproval(approvalKey) {
    this.safetyShield.approveAction(approvalKey);
    this.isRunning = true;
    this._setState('ACTING');
    this.runLoop();
  }

  async runLoop() {
    while (this.isRunning && this.stepIndex < this.maxSteps) {
      this.stepIndex++;

      // 1. Observe: Capture and Prune screen
      this._setState('OBSERVING');
      const rawScreen = this.environment.getCurrentScreen();
      const preState = this.pruner.prune(rawScreen, {
        packageName: rawScreen.packageName,
        appName: rawScreen.appName
      });

      this.callbacks.onStep({
        stepIndex: this.stepIndex,
        stage: 'OBSERVATION',
        screen: rawScreen,
        prunedState: preState
      });

      // 2. Think: Model inference (Gemma 4)
      this._setState('THINKING');
      let plan;
      try {
        plan = await this.modelEngine.generateActionStep({
          userGoal: this.currentGoal,
          prunedState: preState,
          history: this.history
        });
      } catch (err) {
        this.callbacks.onError(`Inference error: ${err.message}`);
        this.stop();
        return;
      }

      this.callbacks.onThought({
        stepIndex: this.stepIndex,
        thought: plan.thought,
        plan
      });

      // Check if model declared error, missing setup, or finish
      if (plan.action === 'error' || plan.needsModelSetup) {
        this._setState('IDLE');
        this.isRunning = false;
        this.callbacks.onError(plan.payload || 'Model is not configured.');
        return;
      }

      if (plan.action === 'finish') {
        this._setState('COMPLETED');
        this.isRunning = false;
        this.callbacks.onComplete({
          success: true,
          goal: this.currentGoal,
          totalSteps: this.stepIndex,
          isConversational: plan.isConversational || false,
          summary: plan.payload || 'Task successfully accomplished and verified.'
        });
        return;
      }

      // 3. Resolve Target Element
      let targetElement = null;
      if (plan.target_id) {
        targetElement = preState.elementMap.get(plan.target_id) || null;
      }

      const candidateAction = {
        type: plan.action,
        targetId: plan.target_id,
        targetElement,
        payload: plan.payload,
        label: targetElement ? targetElement.label : plan.payload
      };

      // 4. Safety Guard Evaluation
      this._setState('SAFETY_CHECK');
      const safetyResult = this.safetyShield.evaluate(candidateAction, {
        packageName: preState.packageName,
        appName: rawScreen.appName
      });

      if (!safetyResult.allowed) {
        if (safetyResult.requiresConfirmation) {
          this.isRunning = false;
          this._setState('PAUSED');
          this.callbacks.onSafetyPrompt({
            safetyResult,
            action: candidateAction,
            stepIndex: this.stepIndex
          });
          return; // Wait for user approval
        } else {
          this.callbacks.onError(`Action blocked by Safety Shield: ${safetyResult.reason}`);
          this.stop();
          return;
        }
      }

      // Record in audit log
      this.safetyShield.recordAction(candidateAction);

      // 5. Execute Action on Environment
      this._setState('ACTING');
      this.callbacks.onAction({
        stepIndex: this.stepIndex,
        action: candidateAction
      });

      await this.environment.executeAction(candidateAction);

      // 6. Settle: Wait for UI animations & screen transitions
      this._setState('SETTLING');
      await new Promise(r => setTimeout(r, this.verifier.settleDelayMs));

      // 7. Verify: Capture post-state and diff
      this._setState('VERIFYING');
      const postRawScreen = this.environment.getCurrentScreen();
      const postState = this.pruner.prune(postRawScreen, {
        packageName: postRawScreen.packageName,
        appName: postRawScreen.appName
      });

      const verificationResult = this.verifier.verify(candidateAction, preState, postState);
      this.callbacks.onVerification({
        stepIndex: this.stepIndex,
        action: candidateAction,
        verification: verificationResult,
        preHash: preState.stateHash,
        postHash: postState.stateHash
      });

      // Record step into history for agent reflection
      this.history.push({
        step: this.stepIndex,
        action: candidateAction,
        preHash: preState.stateHash,
        postHash: postState.stateHash,
        verification: verificationResult
      });

      // Self-Correction & Reflection if action had no effect
      if (!verificationResult.success) {
        console.warn(`[NovaClaw AgentLoop] Step ${this.stepIndex} action failed verification:`, verificationResult.reflection);
      }

      // Slight pacing delay between steps for smooth visual tracking
      await new Promise(r => setTimeout(r, 450));
    }

    if (this.stepIndex >= this.maxSteps && this.isRunning) {
      this.stop();
      this.callbacks.onComplete({
        success: false,
        summary: `Reached maximum step limit (${this.maxSteps}). Halting for safety.`
      });
    }
  }

  _setState(newState) {
    this.state = newState;
    this.callbacks.onStateChange(newState);
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { AgentLoop };
}
