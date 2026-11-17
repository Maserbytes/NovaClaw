/**
 * NovaClaw Application Controller (Mobile-Optimized)
 * Bridges ChatGPT-style frontend to real Android Accessibility Service (or simulation).
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. Initialize Engines
  const pruner = new SemanticUIPruner({ viewportWidth: window.innerWidth || 390, viewportHeight: window.innerHeight || 844 });
  const safetyShield = new SafetyShield();
  const verifier = new ActionVerifier({ settleDelayMs: 400 });
  const modelEngine = new ModelEngine();

  // Detect whether running inside the native Android APK
  const isAndroidNative = typeof window.AndroidBridge !== 'undefined';

  // 2. Real Device Environment vs Web Simulation Environment
  class DeviceEnvironment {
    constructor() {
      this.isNative = isAndroidNative;
      this.simScreen = MOCK_APPS.HOME_SCREEN;
      this.activeConversationText = '';
    }

    getCurrentScreen() {
      if (this.isNative) {
        try {
          const rawJson = window.AndroidBridge.captureScreen();
          const parsed = JSON.parse(rawJson || '{}');
          if (parsed.elements && parsed.elements.length > 0) {
            return {
              packageName: parsed.packageName || 'active.app',
              appName: parsed.packageName || 'Active App',
              elements: parsed.elements.map(e => ({
                id: e.id,
                type: e.type,
                label: e.label,
                text: e.label,
                clickable: e.clickable,
                editable: e.editable,
                scrollable: e.scrollable,
                bounds: { x: (e.center?.x || 100) - 20, y: (e.center?.y || 100) - 20, width: 40, height: 40 },
                center: e.center || { x: 100, y: 100 }
              }))
            };
          }
        } catch (e) {
          console.warn('[NovaClaw] Failed to capture real screen:', e);
        }
      }
      return this.simScreen;
    }

    async executeAction(action) {
      if (this.isNative) {
        if (action.type === 'launch_app') {
          window.AndroidBridge.launchApp(action.payload);
        } else if (action.type === 'click' && action.targetElement && action.targetElement.center) {
          window.AndroidBridge.performTap(action.targetElement.center.x, action.targetElement.center.y);
        } else if (action.type === 'type') {
          window.AndroidBridge.performText(action.targetId || 1, action.payload || '');
        } else if (action.type === 'key_back') {
          window.AndroidBridge.performBack();
        } else if (action.type === 'key_home') {
          window.AndroidBridge.performHome();
        }
        // Brief delay to let the phone UI update
        await new Promise(r => setTimeout(r, 500));
        return;
      }

      // Simulation mode fallback
      if (action.type === 'launch_app') {
        const pkg = (action.payload || '').toLowerCase();
        if (pkg.includes('whatsapp')) this.simScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
        else if (pkg.includes('ubereats')) this.simScreen = MOCK_APPS.UBER_EATS_CATALOG;
        else if (pkg.includes('settings')) this.simScreen = MOCK_APPS.SETTINGS_MAIN;
        else if (pkg.includes('bank')) this.simScreen = MOCK_APPS.BANK_PROTECTED_APP;
      } else if (action.type === 'click') {
        const label = (action.targetElement?.label || '').toLowerCase();
        if (label.includes('sarah') || action.targetId === 7) {
          this.simScreen = MOCK_APPS.WHATSAPP_CHAT_CONVERSATION;
        } else if (label.includes('latte') || label.includes('coffee')) {
          this.simScreen = MOCK_APPS.UBER_EATS_PRODUCT;
        } else if (label.includes('battery')) {
          this.simScreen = MOCK_APPS.SETTINGS_BATTERY;
        }
      } else if (action.type === 'type') {
        this.activeConversationText = action.payload;
      }
    }
  }

  const deviceEnv = new DeviceEnvironment();

  // 3. DOM References
  const chatScrollContainer = document.getElementById('chatScrollContainer');
  const messagesList = document.getElementById('messagesList');
  const welcomeContainer = document.getElementById('welcomeContainer');
  const promptForm = document.getElementById('promptForm');
  const promptInput = document.getElementById('promptInput');
  const sendBtn = document.getElementById('sendBtn');
  const activeStatusBar = document.getElementById('activeStatusBar');
  const agentStateText = document.getElementById('agentStateText');
  const currentStepText = document.getElementById('currentStepText');
  const floatingAbortBtn = document.getElementById('floatingAbortBtn');
  const permissionBanner = document.getElementById('permissionBanner');
  const permissionTitle = document.getElementById('permissionTitle');
  const permissionDesc = document.getElementById('permissionDesc');
  const fixPermissionBtn = document.getElementById('fixPermissionBtn');
  const modelDropdownBtn = document.getElementById('modelDropdownBtn');
  const currentModelLabel = document.getElementById('currentModelLabel');
  const modelSheetModal = document.getElementById('modelSheetModal');
  const closeModelSheetBtn = document.getElementById('closeModelSheetBtn');
  const saveModelSheetBtn = document.getElementById('saveModelSheetBtn');
  const safetyModal = document.getElementById('safetyModal');
  const safetyRiskBadge = document.getElementById('safetyRiskBadge');
  const safetyModalReason = document.getElementById('safetyModalReason');
  const safetyModalTarget = document.getElementById('safetyModalTarget');
  const safetyApproveBtn = document.getElementById('safetyApproveBtn');
  const safetyDenyBtn = document.getElementById('safetyDenyBtn');

  let activeSafetyApprovalKey = null;

  // 4. Permission Banner Manager (Android Native)
  function checkAndRenderPermissions() {
    if (!isAndroidNative) return;

    const hasA11y = window.AndroidBridge.isAccessibilityEnabled();
    const hasOverlay = window.AndroidBridge.isOverlayEnabled();

    if (!hasA11y) {
      permissionBanner.classList.remove('hidden');
      permissionTitle.textContent = 'Accessibility Access Needed';
      permissionDesc.textContent = 'Enable NovaClaw in Settings to let the agent navigate apps.';
      fixPermissionBtn.textContent = 'Enable';
      fixPermissionBtn.onclick = () => window.AndroidBridge.openAccessibilitySettings();
    } else if (!hasOverlay) {
      permissionBanner.classList.remove('hidden');
      permissionTitle.textContent = 'Floating Pill Permission';
      permissionDesc.textContent = 'Allow display over apps for the instant 0ms kill-switch.';
      fixPermissionBtn.textContent = 'Grant';
      fixPermissionBtn.onclick = () => window.AndroidBridge.openOverlaySettings();
    } else {
      permissionBanner.classList.add('hidden');
    }
  }

  // Global hook called by Android onResume
  window.updatePermissionStatus = (isA11y, isOverlay) => {
    checkAndRenderPermissions();
  };

  checkAndRenderPermissions();

  // 5. Agent Loop Setup
  const agent = new AgentLoop({
    pruner,
    safetyShield,
    verifier,
    modelEngine,
    environment: deviceEnv,
    maxSteps: 15,

    onStateChange: (state) => {
      if (agentStateText) agentStateText.textContent = state;
    },

    onStep: (data) => {
      activeStatusBar.classList.remove('hidden');
      if (currentStepText) currentStepText.textContent = `Step ${data.stepIndex}/15`;
    },

    onThought: (data) => {
      appendThoughtCard(data.stepIndex, data.thought, data.plan);
    },

    onAction: (data) => {
      appendActionCard(data.stepIndex, data.action);
    },

    onVerification: (data) => {
      appendVerificationCard(data.stepIndex, data.verification, data.preHash, data.postHash);
    },

    onSafetyPrompt: (promptData) => {
      activeSafetyApprovalKey = promptData.safetyResult.approvalKey;
      safetyRiskBadge.textContent = `Risk Level: ${promptData.safetyResult.riskLevel}`;
      safetyModalReason.textContent = promptData.safetyResult.reason;
      safetyModalTarget.textContent = promptData.safetyResult.promptText;
      safetyModal.classList.remove('hidden');
    },

    onComplete: (res) => {
      activeStatusBar.classList.add('hidden');
      sendBtn.disabled = false;
      appendCompletionCard(res);
    },

    onError: (err) => {
      activeStatusBar.classList.add('hidden');
      sendBtn.disabled = false;
      appendErrorCard(err);
    }
  });

  // 6. Chat Message Rendering (ChatGPT Mobile Style)
  let currentStepContainer = null;

  function addUserMessage(text) {
    if (welcomeContainer) welcomeContainer.classList.add('hidden');

    const wrapper = document.createElement('div');
    wrapper.className = 'flex justify-end';
    wrapper.innerHTML = `
      <div class="max-w-[85%] bg-slate-900 text-white text-xs font-medium px-4 py-2.5 rounded-2xl rounded-tr-sm shadow-sm leading-relaxed">
        ${escapeHtml(text)}
      </div>
    `;
    messagesList.appendChild(wrapper);
    scrollToBottom();

    // Create assistant message container
    createAssistantMessage();
  }

  function createAssistantMessage() {
    const wrapper = document.createElement('div');
    wrapper.className = 'flex items-start gap-2.5';
    wrapper.innerHTML = `
      <div class="w-7 h-7 rounded-xl bg-purple-600 text-white flex items-center justify-center font-bold text-xs shrink-0 shadow-sm mt-0.5">
        ✦
      </div>
      <div class="flex-1 space-y-2 min-w-0 assistant-body">
      </div>
    `;
    messagesList.appendChild(wrapper);
    currentStepContainer = wrapper.querySelector('.assistant-body');
    scrollToBottom();
  }

  function appendThoughtCard(stepIndex, thoughtText, plan) {
    if (!currentStepContainer) createAssistantMessage();

    const card = document.createElement('div');
    card.id = `step-card-${stepIndex}`;
    card.className = 'p-3 bg-white rounded-2xl border border-slate-200 shadow-sm space-y-2 text-xs';

    card.innerHTML = `
      <div class="flex items-center justify-between font-bold text-slate-800 text-[11px]">
        <div class="flex items-center gap-1.5">
          <span class="w-4 h-4 rounded-full bg-purple-100 text-purple-700 flex items-center justify-center text-[10px]">
            ${stepIndex}
          </span>
          <span>Tactical Step</span>
        </div>
        <span class="text-[10px] text-slate-400 font-mono">Gemma 4 • LiteRT</span>
      </div>

      <!-- Collapsible Thought Accordion -->
      <div class="thought-toggle" onclick="this.nextElementSibling.classList.toggle('hidden')">
        <span class="font-semibold flex items-center gap-1">💭 Reasoning & Plan</span>
        <span class="text-[10px]">▾</span>
      </div>
      <div class="thought-content">
        ${escapeHtml(thoughtText)}
      </div>

      <div class="action-slot pt-1"></div>
      <div class="verify-slot"></div>
    `;

    currentStepContainer.appendChild(card);
    scrollToBottom();
  }

  function appendActionCard(stepIndex, action) {
    const card = document.getElementById(`step-card-${stepIndex}`);
    if (!card) return;

    const slot = card.querySelector('.action-slot');
    let chipClass = 'step-chip-click';
    let icon = '👆';

    if (action.type === 'type') {
      chipClass = 'step-chip-type';
      icon = '⌨️';
    } else if (action.type === 'launch_app') {
      chipClass = 'step-chip-launch';
      icon = '🚀';
    }

    slot.innerHTML = `
      <div class="flex items-center gap-2 text-xs">
        <span class="step-chip ${chipClass}">
          ${icon} ${action.type}
        </span>
        <span class="font-medium text-slate-700 truncate">
          ${action.targetId ? `Mark [${action.targetId}]` : ''} 
          ${action.label ? `"${action.label}"` : (action.payload || '')}
        </span>
      </div>
    `;
    scrollToBottom();
  }

  function appendVerificationCard(stepIndex, ver, preHash, postHash) {
    const card = document.getElementById(`step-card-${stepIndex}`);
    if (!card) return;

    const slot = card.querySelector('.verify-slot');
    slot.innerHTML = `
      <div class="mt-1 pt-1 border-t border-slate-100 flex items-start gap-1.5 text-[11px] ${ver.success ? 'text-emerald-700' : 'text-amber-700'}">
        <span>${ver.success ? '✓' : '⚠️'}</span>
        <div class="flex-1 leading-snug">
          <span class="font-semibold">${ver.message}</span>
          <span class="text-[10px] text-slate-400 font-mono block">Diff: ${preHash} → ${postHash}</span>
        </div>
      </div>
    `;
    scrollToBottom();
  }

  function appendCompletionCard(res) {
    if (!currentStepContainer) return;

    const card = document.createElement('div');
    card.className = 'p-3 bg-gradient-to-r from-emerald-50 to-teal-50 border border-emerald-200 rounded-2xl text-xs text-emerald-900 shadow-sm';
    card.innerHTML = `
      <div class="font-bold flex items-center gap-1.5 mb-1">
        <span>🎉</span> Task Completed Successfully
      </div>
      <p class="text-[11px] text-emerald-800 leading-relaxed">${res.summary}</p>
    `;
    currentStepContainer.appendChild(card);
    scrollToBottom();
  }

  function appendErrorCard(err) {
    if (!currentStepContainer) return;

    const card = document.createElement('div');
    card.className = 'p-3 bg-rose-50 border border-rose-200 rounded-2xl text-xs text-rose-800 shadow-sm';
    card.innerHTML = `
      <div class="font-bold flex items-center gap-1.5 mb-0.5">
        <span>🛑</span> Execution Paused
      </div>
      <p class="text-[11px]">${err}</p>
    `;
    currentStepContainer.appendChild(card);
    scrollToBottom();
  }

  function scrollToBottom() {
    if (chatScrollContainer) {
      chatScrollContainer.scrollTop = chatScrollContainer.scrollHeight;
    }
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // 7. Event Listeners
  promptForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = promptInput.value.trim();
    if (!text) return;

    // Check permissions on native device before starting
    if (isAndroidNative && !window.AndroidBridge.isAccessibilityEnabled()) {
      window.AndroidBridge.showToast('Please enable Accessibility Service first!');
      window.AndroidBridge.openAccessibilitySettings();
      return;
    }

    addUserMessage(text);
    promptInput.value = '';
    sendBtn.disabled = true;
    agent.start(text);
  });

  // Preset quick prompt chips
  document.querySelectorAll('.preset-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      const text = btn.getAttribute('data-prompt');
      if (text) {
        if (isAndroidNative && !window.AndroidBridge.isAccessibilityEnabled()) {
          window.AndroidBridge.showToast('Please enable Accessibility Service first!');
          window.AndroidBridge.openAccessibilitySettings();
          return;
        }
        addUserMessage(text);
        sendBtn.disabled = true;
        agent.start(text);
      }
    });
  });

  floatingAbortBtn.addEventListener('click', () => {
    agent.stop();
  });

  // Safety Confirmation Handlers
  safetyApproveBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    if (activeSafetyApprovalKey) {
      agent.resumeAfterSafetyApproval(activeSafetyApprovalKey);
      activeSafetyApprovalKey = null;
    }
  });

  safetyDenyBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    agent.stop();
    activeSafetyApprovalKey = null;
    appendErrorCard('User denied authorization for sensitive action.');
  });

  // Model Sheet Modal
  modelDropdownBtn.addEventListener('click', () => modelSheetModal.classList.remove('hidden'));
  closeModelSheetBtn.addEventListener('click', () => modelSheetModal.classList.add('hidden'));
  saveModelSheetBtn.addEventListener('click', () => modelSheetModal.classList.add('hidden'));

  document.querySelectorAll('.model-option').forEach(opt => {
    opt.addEventListener('click', () => {
      document.querySelectorAll('.model-option').forEach(o => {
        o.className = 'model-option p-3 rounded-2xl border border-slate-200 bg-white hover:bg-slate-50 flex items-center justify-between cursor-pointer';
        const span = o.querySelector('span:last-child');
        if (span) span.textContent = 'Select';
      });
      opt.className = 'model-option p-3 rounded-2xl border border-purple-500 bg-purple-50/50 flex items-center justify-between cursor-pointer';
      const check = opt.querySelector('span:last-child');
      if (check) check.textContent = '✓';

      const modelId = opt.getAttribute('data-model');
      const selected = modelEngine.setModel(modelId);
      currentModelLabel.textContent = selected.name.split(' (')[0];
    });
  });
});
