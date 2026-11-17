/**
 * NovaClaw Application Controller
 * Replicates the clean, minimalist ChatGPT Mobile UI with slide-out navigation drawer.
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. Initialize Engines
  const pruner = new SemanticUIPruner({ viewportWidth: window.innerWidth || 390, viewportHeight: window.innerHeight || 844 });
  const safetyShield = new SafetyShield();
  const verifier = new ActionVerifier({ settleDelayMs: 350 });
  const modelEngine = new ModelEngine();

  const isAndroidNative = typeof window.AndroidBridge !== 'undefined';

  // 2. Real Mobile Device Environment vs Web Simulation
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
          console.warn('[NovaClaw] Screen capture error:', e);
        }
      }
      return this.simScreen;
    }

    async executeAction(action) {
      if (this.isNative) {
        if (action.type === 'launch_app') {
          window.AndroidBridge.launchApp(action.payload);
        } else if (action.type === 'click' && action.targetElement?.center) {
          window.AndroidBridge.performTap(action.targetElement.center.x, action.targetElement.center.y);
        } else if (action.type === 'type') {
          window.AndroidBridge.performText(action.targetId || 1, action.payload || '');
        } else if (action.type === 'key_back') {
          window.AndroidBridge.performBack();
        } else if (action.type === 'key_home') {
          window.AndroidBridge.performHome();
        }
        await new Promise(r => setTimeout(r, 450));
        return;
      }

      // Simulation fallback
      if (action.type === 'launch_app') {
        const pkg = (action.payload || '').toLowerCase();
        if (pkg.includes('whatsapp')) this.simScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
        else if (pkg.includes('ubereats')) this.simScreen = MOCK_APPS.UBER_EATS_CATALOG;
        else if (pkg.includes('settings')) this.simScreen = MOCK_APPS.SETTINGS_MAIN;
        else if (pkg.includes('bank')) this.simScreen = MOCK_APPS.BANK_PROTECTED_APP;
      } else if (action.type === 'click') {
        const label = (action.targetElement?.label || '').toLowerCase();
        if (label.includes('sarah') || action.targetId === 7) this.simScreen = MOCK_APPS.WHATSAPP_CHAT_CONVERSATION;
        else if (label.includes('latte') || label.includes('coffee')) this.simScreen = MOCK_APPS.UBER_EATS_PRODUCT;
        else if (label.includes('battery')) this.simScreen = MOCK_APPS.SETTINGS_BATTERY;
      } else if (action.type === 'type') {
        this.activeConversationText = action.payload;
      }
    }
  }

  const deviceEnv = new DeviceEnvironment();

  // 3. UI Element References
  const openDrawerBtn = document.getElementById('openDrawerBtn');
  const closeDrawerBtn = document.getElementById('closeDrawerBtn');
  const sideDrawer = document.getElementById('sideDrawer');
  const drawerBackdrop = document.getElementById('drawerBackdrop');
  const topNewChatBtn = document.getElementById('topNewChatBtn');
  const drawerNewChatBtn = document.getElementById('drawerNewChatBtn');
  const homePromptSection = document.getElementById('homePromptSection');
  const messageStream = document.getElementById('messageStream');
  const chatViewport = document.getElementById('chatViewport');
  const chatForm = document.getElementById('chatForm');
  const chatInput = document.getElementById('chatInput');
  const sendActionBtn = document.getElementById('sendActionBtn');
  const executionStatusBar = document.getElementById('executionStatusBar');
  const agentStateLabel = document.getElementById('agentStateLabel');
  const stepCounterLabel = document.getElementById('stepCounterLabel');
  const stopExecutionBtn = document.getElementById('stopExecutionBtn');
  const drawerA11yBtn = document.getElementById('drawerA11yBtn');
  const drawerA11yBadge = document.getElementById('drawerA11yBadge');
  const drawerOverlayBtn = document.getElementById('drawerOverlayBtn');
  const drawerOverlayBadge = document.getElementById('drawerOverlayBadge');
  const drawerModelBtn = document.getElementById('drawerModelBtn');
  const activeModelShortLabel = document.getElementById('activeModelShortLabel');
  const modelModal = document.getElementById('modelModal');
  const closeModelModalBtn = document.getElementById('closeModelModalBtn');
  const doneModelModalBtn = document.getElementById('doneModelModalBtn');
  const safetyModal = document.getElementById('safetyModal');
  const safetyReasonText = document.getElementById('safetyReasonText');
  const safetyTargetText = document.getElementById('safetyTargetText');
  const safetyBlockBtn = document.getElementById('safetyBlockBtn');
  const safetyAllowBtn = document.getElementById('safetyAllowBtn');

  let activeSafetyApprovalKey = null;

  // 4. Side Drawer Controller
  function openDrawer() {
    sideDrawer.classList.remove('-translate-x-full');
    drawerBackdrop.classList.remove('hidden');
    updateDrawerPermissions();
  }

  function closeDrawer() {
    sideDrawer.classList.add('-translate-x-full');
    drawerBackdrop.classList.add('hidden');
  }

  openDrawerBtn.addEventListener('click', openDrawer);
  closeDrawerBtn.addEventListener('click', closeDrawer);
  drawerBackdrop.addEventListener('click', closeDrawer);

  // 5. Drawer Permission Hooks
  function updateDrawerPermissions() {
    if (!isAndroidNative) return;
    const a11y = window.AndroidBridge.isAccessibilityEnabled();
    const overlay = window.AndroidBridge.isOverlayEnabled();

    if (a11y) {
      drawerA11yBadge.textContent = 'Active';
      drawerA11yBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
    } else {
      drawerA11yBadge.textContent = 'Enable';
      drawerA11yBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-amber-900/60 text-amber-300 border border-amber-700/60 animate-pulse';
    }

    if (overlay) {
      drawerOverlayBadge.textContent = 'Active';
      drawerOverlayBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
    } else {
      drawerOverlayBadge.textContent = 'Grant';
      drawerOverlayBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-slate-800 text-slate-400';
    }
  }

  drawerA11yBtn.addEventListener('click', () => {
    if (isAndroidNative) {
      window.AndroidBridge.openAccessibilitySettings();
    } else {
      alert('Running in browser simulation mode. On Android, this opens system Accessibility Settings.');
    }
  });

  drawerOverlayBtn.addEventListener('click', () => {
    if (isAndroidNative) {
      window.AndroidBridge.openOverlaySettings();
    }
  });

  // Global callback from Android onResume
  window.updatePermissionStatus = () => {
    updateDrawerPermissions();
  };

  updateDrawerPermissions();

  // 6. Reset to New Chat
  function resetToNewChat() {
    agent.stop();
    messageStream.innerHTML = '';
    homePromptSection.classList.remove('hidden');
    executionStatusBar.classList.add('hidden');
    closeDrawer();
  }

  topNewChatBtn.addEventListener('click', resetToNewChat);
  drawerNewChatBtn.addEventListener('click', resetToNewChat);

  // 7. Agent Loop Instance
  const agent = new AgentLoop({
    pruner,
    safetyShield,
    verifier,
    modelEngine,
    environment: deviceEnv,
    maxSteps: 15,

    onStateChange: (state) => {
      if (agentStateLabel) agentStateLabel.textContent = state;
    },

    onStep: (data) => {
      executionStatusBar.classList.remove('hidden');
      if (stepCounterLabel) stepCounterLabel.textContent = `Step ${data.stepIndex}/15`;
    },

    onThought: (data) => {
      appendThoughtToStream(data.stepIndex, data.thought, data.plan);
    },

    onAction: (data) => {
      appendActionToStream(data.stepIndex, data.action);
    },

    onVerification: (data) => {
      appendVerificationToStream(data.stepIndex, data.verification, data.preHash, data.postHash);
    },

    onSafetyPrompt: (promptData) => {
      activeSafetyApprovalKey = promptData.safetyResult.approvalKey;
      safetyReasonText.textContent = promptData.safetyResult.reason;
      safetyTargetText.textContent = promptData.safetyResult.promptText;
      safetyModal.classList.remove('hidden');
    },

    onComplete: (res) => {
      executionStatusBar.classList.add('hidden');
      appendCompletionToStream(res);
    },

    onError: (err) => {
      executionStatusBar.classList.add('hidden');
      appendErrorToStream(err);
    }
  });

  // 8. Stream Rendering Helpers (ChatGPT Mobile Style)
  let activeAssistantContainer = null;

  function addUserMessage(text) {
    homePromptSection.classList.add('hidden');

    const row = document.createElement('div');
    row.className = 'flex justify-end my-2';
    row.innerHTML = `
      <div class="user-bubble">
        ${escapeHtml(text)}
      </div>
    `;
    messageStream.appendChild(row);
    scrollToBottom();

    // Create assistant response slot
    createAssistantSlot();
  }

  function createAssistantSlot() {
    const row = document.createElement('div');
    row.className = 'flex flex-col gap-2 my-2 assistant-bubble';
    row.innerHTML = `<div class="assistant-content space-y-2"></div>`;
    messageStream.appendChild(row);
    activeAssistantContainer = row.querySelector('.assistant-content');
    scrollToBottom();
  }

  function appendThoughtToStream(stepIndex, thoughtText, plan) {
    if (!activeAssistantContainer) createAssistantSlot();

    const block = document.createElement('div');
    block.id = `turn-step-${stepIndex}`;
    block.className = 'space-y-1 text-xs';

    block.innerHTML = `
      <!-- Collapsible Thought Pill (ChatGPT Style) -->
      <div>
        <div class="thought-pill" onclick="this.nextElementSibling.classList.toggle('hidden')">
          <span>💭 Step ${stepIndex} Reasoning</span>
          <span class="text-[9px]">▾</span>
        </div>
        <div class="thought-drawer hidden">
          ${escapeHtml(thoughtText)}
        </div>
      </div>
      <div class="action-row pt-0.5"></div>
      <div class="verify-row"></div>
    `;

    activeAssistantContainer.appendChild(block);
    scrollToBottom();
  }

  function appendActionToStream(stepIndex, action) {
    const block = document.getElementById(`turn-step-${stepIndex}`);
    if (!block) return;

    const row = block.querySelector('.action-row');
    let tagClass = 'action-tag-click';
    let icon = '👆';

    if (action.type === 'type') {
      tagClass = 'action-tag-type';
      icon = '⌨️';
    } else if (action.type === 'launch_app') {
      tagClass = 'action-tag-launch';
      icon = '🚀';
    }

    row.innerHTML = `
      <div class="flex items-center gap-2 text-xs text-slate-300">
        <span class="action-tag ${tagClass}">
          ${icon} ${action.type}
        </span>
        <span class="truncate">
          ${action.targetId ? `Mark [${action.targetId}]` : ''} 
          ${action.label ? `"${action.label}"` : (action.payload || '')}
        </span>
      </div>
    `;
    scrollToBottom();
  }

  function appendVerificationToStream(stepIndex, ver, preHash, postHash) {
    const block = document.getElementById(`turn-step-${stepIndex}`);
    if (!block) return;

    const row = block.querySelector('.verify-row');
    row.innerHTML = `
      <div class="text-[11px] ${ver.success ? 'text-emerald-400' : 'text-amber-400'} flex items-center gap-1.5 pl-1">
        <span>${ver.success ? '✓' : '⚠️'}</span>
        <span>${ver.message}</span>
      </div>
    `;
    scrollToBottom();
  }

  function appendCompletionToStream(res) {
    if (!activeAssistantContainer) return;

    const summary = document.createElement('div');
    summary.className = 'pt-2 text-xs text-slate-200 leading-relaxed';
    summary.innerHTML = `
      <div class="p-3 rounded-2xl bg-[#1C1C1E] border border-[#2C2C2E]">
        <div class="font-bold text-white flex items-center gap-1.5 mb-1">
          <span>✨</span> Done
        </div>
        <p class="text-slate-300 text-xs">${res.summary}</p>
      </div>
    `;
    activeAssistantContainer.appendChild(summary);
    scrollToBottom();
  }

  function appendErrorToStream(err) {
    if (!activeAssistantContainer) return;

    const errBox = document.createElement('div');
    errBox.className = 'p-3 rounded-2xl bg-rose-950/40 border border-rose-800 text-xs text-rose-300';
    errBox.innerHTML = `<strong>Halted:</strong> ${err}`;
    activeAssistantContainer.appendChild(errBox);
    scrollToBottom();
  }

  function scrollToBottom() {
    if (chatViewport) {
      chatViewport.scrollTop = chatViewport.scrollHeight;
    }
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // 9. Input & Preset Handlers
  function handleUserPrompt(text) {
    if (!text) return;

    if (isAndroidNative && !window.AndroidBridge.isAccessibilityEnabled()) {
      window.AndroidBridge.showToast('Please enable Accessibility Service first!');
      openDrawer();
      return;
    }

    addUserMessage(text);
    agent.start(text);
  }

  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = chatInput.value.trim();
    if (!text) return;
    chatInput.value = '';
    handleUserPrompt(text);
  });

  // Suggestion rows
  document.querySelectorAll('.chatgpt-suggestion-row').forEach(row => {
    row.addEventListener('click', () => {
      const text = row.getAttribute('data-prompt');
      handleUserPrompt(text);
    });
  });

  stopExecutionBtn.addEventListener('click', () => {
    agent.stop();
  });

  // Safety Confirmation
  safetyAllowBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    if (activeSafetyApprovalKey) {
      agent.resumeAfterSafetyApproval(activeSafetyApprovalKey);
      activeSafetyApprovalKey = null;
    }
  });

  safetyBlockBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    agent.stop();
    activeSafetyApprovalKey = null;
    appendErrorToStream('Blocked by user.');
  });

  // Model Modal
  drawerModelBtn.addEventListener('click', () => {
    closeDrawer();
    modelModal.classList.remove('hidden');
  });

  closeModelModalBtn.addEventListener('click', () => modelModal.classList.add('hidden'));
  doneModelModalBtn.addEventListener('click', () => modelModal.classList.add('hidden'));

  document.querySelectorAll('.model-choice').forEach(choice => {
    choice.addEventListener('click', () => {
      document.querySelectorAll('.model-choice').forEach(c => {
        c.className = 'model-choice p-3 rounded-2xl bg-[#262626] border border-transparent hover:border-slate-600 cursor-pointer flex justify-between items-center';
        c.querySelector('span:last-child').textContent = 'Select';
      });
      choice.className = 'model-choice p-3 rounded-2xl bg-[#2C2C2E] border border-purple-500/80 cursor-pointer flex justify-between items-center';
      choice.querySelector('span:last-child').textContent = '✓';

      const modelId = choice.getAttribute('data-id');
      const selected = modelEngine.setModel(modelId);
      activeModelShortLabel.textContent = selected.name.split(' (')[0];
    });
  });
});
