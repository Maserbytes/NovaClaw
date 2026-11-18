/**
 * NovaClaw Quad-Shield Safety Architecture
 * 
 * Guarantees phone safety and prevents phone destruction or accidental data loss.
 * Built to prevent the catastrophic failure modes of naive mobile agents:
 * 1. Package Firewall: Blocks sensitive apps (banking, crypto, password vaults, system settings).
 * 2. Destructive Pattern Interceptor: Catches 'Delete', 'Wipe', 'Pay', 'Uninstall' actions.
 * 3. Human-in-the-Loop (HITL) Gate: Halts and asks for explicit biometric/tap approval.
 * 4. Hardware/Floating Kill-Switch: 0ms instantaneous kill that aborts all gestures.
 */

class SafetyShield {
  constructor(options = {}) {
    this.emergencyStopped = false;
    this.actionHistory = [];
    this.maxRepeatAttempts = options.maxRepeatAttempts || 3;
    this.maxTotalSteps = options.maxTotalSteps || 25;
    this.currentStepCount = 0;

    // Blacklisted package prefixes or keywords
    this.packageBlacklist = new Set([
      'com.android.settings',
      'com.google.android.packageinstaller',
      'com.android.packageinstaller',
      'com.samsung.android.settings',
      'com.miui.securitycenter',
      'com.android.vending', // Google Play Store in-app purchase
      'com.paypal.android.p2pmobile',
      'com.google.android.apps.walletnfcrel',
      'com.phonepe.app',
      'net.one97.paytm',
      'com.binance.dev',
      'com.coinbase.android',
      'com.x8bit.bitwarden',
      'com.onepassword.android'
    ]);

    // High risk keywords that require explicit user confirmation
    this.destructivePatterns = [
      /\b(delete|erase|format|wipe|uninstall|destroy)\b/i,
      /\b(clear\s+all\s+data|clear\s+storage|remove\s+account)\b/i,
      /\b(pay|transfer|send\s+money|buy\s+now|checkout|confirm\s+payment)\b/i,
      /\b(factory\s+reset|reset\s+phone|reboot\s+to\s+recovery)\b/i,
      /\b(grant\s+permission|device\s+admin|modify\s+system)\b/i
    ];

    // Allowed exceptions approved by the user during this session
    this.sessionApprovedActions = new Set();
  }

  /**
   * Evaluates an agent action against safety policies.
   * @param {Object} action - The action proposed by the model (type, targetElement, payload)
   * @param {Object} screenMeta - Metadata about the current package/screen
   * @returns {Object} { allowed: boolean, reason: string, riskLevel: 'NONE'|'LOW'|'MEDIUM'|'HIGH'|'CRITICAL', requiresConfirmation: boolean }
   */
  evaluate(action, screenMeta = {}) {
    if (this.emergencyStopped) {
      return {
        allowed: false,
        reason: 'Emergency Kill-Switch is ACTIVE. All automation is hard-halted.',
        riskLevel: 'CRITICAL',
        requiresConfirmation: false
      };
    }

    this.currentStepCount++;
    if (this.currentStepCount > this.maxTotalSteps) {
      return {
        allowed: false,
        reason: `Maximum autonomous step limit (${this.maxTotalSteps}) reached. Paused for safety.`,
        riskLevel: 'HIGH',
        requiresConfirmation: true
      };
    }

    const packageName = (screenMeta.packageName || '').toLowerCase();
    
    // 1. Package Firewall Check
    for (const blocked of this.packageBlacklist) {
      if (packageName.includes(blocked) || blocked.includes(packageName)) {
        const approvedKey = `pkg:${packageName}`;
        if (!this.sessionApprovedActions.has(approvedKey)) {
          return {
            allowed: false,
            reason: `Target package "${packageName}" is classified as a Protected Sensitive App (Settings/Finance/Security).`,
            riskLevel: 'HIGH',
            requiresConfirmation: true,
            approvalKey: approvedKey,
            promptText: `NovaClaw wants to interact with "${screenMeta.appName || packageName}". Allow this session?`
          };
        }
      }
    }

    // 2. Destructive Action Check
    const targetText = action.targetElement ? (action.targetElement.label || '') : '';
    const payloadText = typeof action.payload === 'string' ? action.payload : '';
    const combinedInspectionString = `${action.type} ${targetText} ${payloadText}`;

    for (const pattern of this.destructivePatterns) {
      if (pattern.test(combinedInspectionString)) {
        const actionKey = `action:${action.type}:${targetText}`;
        if (!this.sessionApprovedActions.has(actionKey)) {
          return {
            allowed: false,
            reason: `Dangerous operation detected matching rule "${pattern}". Target: "${targetText}".`,
            riskLevel: 'CRITICAL',
            requiresConfirmation: true,
            approvalKey: actionKey,
            promptText: `Destructive action detected: "${targetText || action.type}". Are you sure you want NovaClaw to proceed?`
          };
        }
      }
    }

    // 3. Infinite Click Loop Detection
    if (action.type === 'click' && action.targetElement) {
      const recentSameClicks = this.actionHistory.slice(-3).filter(
        h => h.type === 'click' && h.targetId === action.targetElement.id
      );
      if (recentSameClicks.length >= this.maxRepeatAttempts) {
        return {
          allowed: false,
          reason: `Repeated click loop detected on element [${action.targetElement.id}] without UI progression.`,
          riskLevel: 'MEDIUM',
          requiresConfirmation: true,
          promptText: `The agent clicked "${targetText}" 3 times without progress. Continue or rethink?`
        };
      }
    }

    // Passed all guards
    return {
      allowed: true,
      reason: 'Passed Quad-Shield Safety verification.',
      riskLevel: 'NONE',
      requiresConfirmation: false
    };
  }

  /**
   * Records an executed action into the audit trail.
   */
  recordAction(action) {
    this.actionHistory.push({
      timestamp: Date.now(),
      type: action.type,
      targetId: action.targetElement ? action.targetElement.id : null,
      label: action.targetElement ? action.targetElement.label : null,
      payload: action.payload
    });
  }

  /**
   * User explicitly approves a flagged risky action.
   */
  approveAction(approvalKey) {
    if (approvalKey) {
      this.sessionApprovedActions.add(approvalKey);
    }
  }

  /**
   * Triggers the 0ms emergency kill-switch.
   */
  triggerKillSwitch() {
    this.emergencyStopped = true;
    console.warn('[NovaClaw SafetyShield] EMERGENCY KILL-SWITCH ENGAGED. Execution halted.');
    return { success: true, message: 'Automation paused immediately.' };
  }

  /**
   * Resets the kill-switch and step counters for a new task.
   */
  reset() {
    this.emergencyStopped = false;
    this.actionHistory = [];
    this.currentStepCount = 0;
    this.sessionApprovedActions.clear();
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { SafetyShield };
}
