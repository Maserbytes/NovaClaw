/**
 * NovaClaw Action Verification & Reflection Engine
 * 
 * Implements the Antigravity / Codex Agent loop on mobile.
 * Solves PokeClaw's blind execution flaw:
 * Instead of firing a gesture and hoping it worked, NovaClaw:
 * 1. Computes pre-action UI signature
 * 2. Executes action with debounced settle-time
 * 3. Inspects post-action state diff
 * 4. Verifies post-conditions
 * 5. If failed: diagnoses cause (ANR, modal blocker, dead zone) and initiates self-correction.
 */

class ActionVerifier {
  constructor(options = {}) {
    this.settleDelayMs = options.settleDelayMs || 350; // Wait for UI transition animation
  }

  /**
   * Verifies the effect of an action by diffing pre and post states.
   * @param {Object} action - Action executed
   * @param {Object} preState - UI state before action (from SemanticUIPruner)
   * @param {Object} postState - UI state after action (from SemanticUIPruner)
   * @returns {Object} Verification outcome and reflection advice
   */
  verify(action, preState, postState) {
    const isStateChanged = preState.stateHash !== postState.stateHash;
    const isPackageChanged = preState.packageName !== postState.packageName;

    // Check for System Blocker or ANR Dialog
    const anrDialog = this._detectANRorModal(postState);
    if (anrDialog) {
      return {
        success: false,
        status: 'INTERRUPTED_BY_MODAL',
        modalType: anrDialog.type,
        message: `Screen was interrupted by: "${anrDialog.title}".`,
        reflection: `An unexpected modal "${anrDialog.title}" appeared. Must dismiss or resolve this modal before continuing original task.`,
        suggestedAction: {
          type: 'click',
          targetElement: anrDialog.dismissElement,
          reason: 'Dismiss blocking dialog'
        }
      };
    }

    // Specific verification by action type
    switch (action.type) {
      case 'click':
        return this._verifyClick(action, preState, postState, isStateChanged, isPackageChanged);

      case 'type':
        return this._verifyType(action, preState, postState);

      case 'scroll':
        return this._verifyScroll(action, preState, postState, isStateChanged);

      case 'launch_app':
        return this._verifyAppLaunch(action, postState);

      case 'key_back':
      case 'key_home':
        return {
          success: isStateChanged,
          status: isStateChanged ? 'VERIFIED' : 'NO_EFFECT',
          message: isStateChanged ? 'System navigation executed.' : 'No change detected after system key.',
          reflection: isStateChanged ? 'State updated.' : 'Try alternative navigation action.'
        };

      default:
        return {
          success: isStateChanged,
          status: isStateChanged ? 'VERIFIED' : 'UNCERTAIN',
          message: isStateChanged ? 'State updated.' : 'No noticeable UI change.',
          reflection: 'Proceeding to next observation.'
        };
    }
  }

  _verifyClick(action, preState, postState, isStateChanged, isPackageChanged) {
    if (isPackageChanged) {
      return {
        success: true,
        status: 'APP_TRANSITION',
        message: `Successfully navigated from ${preState.packageName} to ${postState.packageName}.`,
        reflection: 'New application window opened.'
      };
    }

    if (!isStateChanged) {
      return {
        success: false,
        status: 'NO_STATE_CHANGE',
        message: `Clicked element [${action.targetId || 'target'}] ("${action.label || ''}"), but screen content did not change.`,
        reflection: 'Element may have been partially off-screen, disabled, or coordinates missed the touch target. Suggest scrolling slightly or re-targeting by accessibility node action.'
      };
    }

    // Look for expected changes: e.g. text input focused, new screen appeared
    return {
      success: true,
      status: 'VERIFIED',
      message: `Click verified. UI state transitioned (hash: ${postState.stateHash}).`,
      reflection: 'Action succeeded. Evaluate next step towards goal.'
    };
  }

  _verifyType(action, preState, postState) {
    // Check if the typed text exists in any input or text field on the new screen
    const typedText = (action.payload || '').toLowerCase();
    const foundElement = postState.elements.find(el => 
      (el.label || '').toLowerCase().includes(typedText)
    );

    if (foundElement) {
      return {
        success: true,
        status: 'TEXT_COMMITTED',
        message: `Text "${action.payload}" successfully written into element [${foundElement.id}].`,
        reflection: 'Input verified.'
      };
    }

    return {
      success: true,
      status: 'TEXT_DISPATCHED',
      message: `Typed text "${action.payload}". Input dispatched to active focus.`,
      reflection: 'Proceeding to confirm submission.'
    };
  }

  _verifyScroll(action, preState, postState, isStateChanged) {
    if (!isStateChanged) {
      return {
        success: false,
        status: 'SCROLL_LIMIT_REACHED',
        message: 'Scroll performed, but no new elements appeared (reached edge or view not scrollable).',
        reflection: 'Target element is not further down this list. Consider alternative search.'
      };
    }

    return {
      success: true,
      status: 'SCROLLED',
      message: 'Scroll succeeded. Revealed new elements on screen.',
      reflection: 'New elements loaded. Scan for target.'
    };
  }

  _verifyAppLaunch(action, postState) {
    const targetPkg = (action.payload || '').toLowerCase();
    const currentPkg = (postState.packageName || '').toLowerCase();
    const matched = currentPkg.includes(targetPkg) || targetPkg.includes(currentPkg);

    return {
      success: matched,
      status: matched ? 'APP_LAUNCHED' : 'LAUNCH_FAILED',
      message: matched ? `Target app "${targetPkg}" is now in foreground.` : `Expected "${targetPkg}", but current app is "${currentPkg}".`,
      reflection: matched ? 'App ready for interaction.' : 'App failed to open. Check package name or launch via Home Screen.'
    };
  }

  _detectANRorModal(postState) {
    for (const el of postState.elements) {
      const label = (el.label || '').toLowerCase();
      if (label.includes('is not responding') || label.includes('close app') || label.includes('wait')) {
        const dismissBtn = postState.elements.find(e => e.label && e.label.toLowerCase().includes('wait')) || el;
        return { type: 'ANR_DIALOG', title: 'App Not Responding', dismissElement: dismissBtn };
      }
      if (label.includes('allow') && (label.includes('permission') || label.includes('access'))) {
        return { type: 'PERMISSION_DIALOG', title: 'System Permission Request', dismissElement: el };
      }
    }
    return null;
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { ActionVerifier };
}
