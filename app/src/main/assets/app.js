document.addEventListener('DOMContentLoaded', () => {
  const modelEngine = new ModelEngine();
  const isAndroidNative = typeof window.AndroidBridge !== 'undefined';

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
  const topStatusIndicator = document.getElementById('topStatusIndicator');
  const headerStatusText = document.getElementById('headerStatusText');
  const headerStatusDot = document.getElementById('headerStatusDot');

  // Drawer Controls
  const drawerA11yBtn = document.getElementById('drawerA11yBtn');
  const drawerA11yBadge = document.getElementById('drawerA11yBadge');
  const drawerAppInfoBtn = document.getElementById('drawerAppInfoBtn');
  const drawerOverlayBtn = document.getElementById('drawerOverlayBtn');
  const drawerOverlayBadge = document.getElementById('drawerOverlayBadge');
  const drawerModelBtn = document.getElementById('drawerModelBtn');
  const activeModelShortLabel = document.getElementById('activeModelShortLabel');
  const drawerModelPill = document.getElementById('drawerModelPill');

  // Setup Banners
  const setupAccessibilityBanner = document.getElementById('setupAccessibilityBanner');
  const setupA11yBtn = document.getElementById('setupA11yBtn');
  const openAppInfoBtn = document.getElementById('openAppInfoBtn');
  const setupModelBanner = document.getElementById('setupModelBanner');
  const setupModelBtn = document.getElementById('setupModelBtn');

  // Model Modal References
  const modelModal = document.getElementById('modelModal');
  const closeModelModalBtn = document.getElementById('closeModelModalBtn');
  const doneModelModalBtn = document.getElementById('doneModelModalBtn');
  const tabOnDeviceBtn = document.getElementById('tabOnDeviceBtn');
  const tabCloudApiBtn = document.getElementById('tabCloudApiBtn');
  const tabOnDeviceContent = document.getElementById('tabOnDeviceContent');
  const tabCloudApiContent = document.getElementById('tabCloudApiContent');

  // Download Gemma Controls
  const downloadGemmaBtn = document.getElementById('downloadGemmaBtn');
  const downloadGemmaBtnText = document.getElementById('downloadGemmaBtnText');
  const gemmaDeleteBtn = document.getElementById('gemmaDeleteBtn');
  const gemmaStatusBadge = document.getElementById('gemmaStatusBadge');
  const gemmaDownloadProgressBox = document.getElementById('gemmaDownloadProgressBox');
  const gemmaProgressBar = document.getElementById('gemmaProgressBar');
  const gemmaProgressText = document.getElementById('gemmaProgressText');
  const gemmaProgressStatus = document.getElementById('gemmaProgressStatus');

  // Download Gemma 4 E4B Controls
  const downloadE4bBtn = document.getElementById('downloadE4bBtn');
  const downloadE4bBtnText = document.getElementById('downloadE4bBtnText');
  const e4bDeleteBtn = document.getElementById('e4bDeleteBtn');
  const e4bStatusBadge = document.getElementById('e4bStatusBadge');
  const e4bDownloadProgressBox = document.getElementById('e4bDownloadProgressBox');
  const e4bProgressBar = document.getElementById('e4bProgressBar');
  const e4bProgressText = document.getElementById('e4bProgressText');
  const e4bProgressStatus = document.getElementById('e4bProgressStatus');

  // Gemini API Controls
  const geminiApiKeyInput = document.getElementById('geminiApiKeyInput');
  const saveGeminiKeyBtn = document.getElementById('saveGeminiKeyBtn');
  const geminiKeyStatus = document.getElementById('geminiKeyStatus');
  const geminiActiveBadge = document.getElementById('geminiActiveBadge');

  // Safety Modal
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
    refreshStatus();
  }

  function closeDrawer() {
    sideDrawer.classList.add('-translate-x-full');
    drawerBackdrop.classList.add('hidden');
  }

  openDrawerBtn.addEventListener('click', openDrawer);
  closeDrawerBtn.addEventListener('click', closeDrawer);
  drawerBackdrop.addEventListener('click', closeDrawer);

  // 5. System Status & Permission Management
  function refreshStatus() {
    let a11yActive = false;
    let overlayActive = false;

    if (isAndroidNative) {
      try {
        a11yActive = window.AndroidBridge.isAccessibilityEnabled();
        overlayActive = window.AndroidBridge.isOverlayEnabled();
      } catch (e) {
        console.warn('Bridge status check error:', e);
      }
    }

    // Update Accessibility Banner & Badges
    if (a11yActive) {
      if (setupAccessibilityBanner) setupAccessibilityBanner.classList.add('hidden');
      if (drawerA11yBadge) {
        drawerA11yBadge.textContent = 'Active';
        drawerA11yBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
      }
    } else {
      if (setupAccessibilityBanner && isAndroidNative) setupAccessibilityBanner.classList.remove('hidden');
      if (drawerA11yBadge) {
        drawerA11yBadge.textContent = 'Enable';
        drawerA11yBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-amber-900/60 text-amber-300 border border-amber-700/60 animate-pulse';
      }
    }

    if (overlayActive && drawerOverlayBadge) {
      drawerOverlayBadge.textContent = 'Active';
      drawerOverlayBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
    }

    // Check Model Status
    checkInstalledModelsStatus();
  }

  function checkInstalledModelsStatus() {
    const legacyQwen = document.getElementById('deleteLegacyQwenBtn');
    legacyQwen?.classList.toggle('hidden', !window.AndroidBridge?.hasLegacyQwenDownload?.());
    const isReady = modelEngine.isModelReady();
    const activeModel = modelEngine.getSelectedModel();

    if (isAndroidNative && window.AndroidBridge.checkModelStatus) {
      try {
        const downloads = [
          ['gemma-4-2b-it', 'gemma', downloadGemmaBtnText, gemmaDeleteBtn],
          ['gemma-4-4b-it', 'e4b', downloadE4bBtnText, e4bDeleteBtn],
          ['qwen-3.5-2b', 'qwen', document.getElementById('downloadQwenBtnText'), null]
        ];
        for (const [id, prefix, buttonLabel, deleteButton] of downloads) {
          const model = AVAILABLE_MODELS.find(item => item.id === id);
          const installed = modelEngine.isModelReady(model);
          const active = installed && activeModel.id === id;
          const progress = document.getElementById(prefix + 'DownloadProgressBox');
          if (installed || progress.classList.contains('hidden')) {
            const badge = document.getElementById(prefix + 'StatusBadge');
            badge.textContent = active ? 'In use' : installed ? 'Installed' : 'Not installed';
            buttonLabel.textContent = active ? 'Selected' : installed ? 'Use this model' : 'Download · ' + model.size;
          }
          if (installed) progress.classList.add('hidden');
          deleteButton?.classList.toggle('hidden', !installed);
        }
      } catch (e) {
        console.warn('Model status check error:', e);
      }
    }

    if (modelEngine.geminiApiKey) {
      if (geminiKeyStatus) geminiKeyStatus.textContent = 'API key saved on this phone.';
      if (saveGeminiKeyBtn) saveGeminiKeyBtn.textContent = 'Use Gemini';
      if (geminiActiveBadge) {
        geminiActiveBadge.textContent = activeModel.kind === 'cloud' ? 'In use' : 'Available';
        geminiActiveBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
      }
    } else {
      if (geminiActiveBadge) {
        geminiActiveBadge.textContent = 'Not Configured';
        geminiActiveBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-slate-800 text-slate-400';
      }
    }

    // Model Banner on Home Screen
    if (isReady) {
      if (setupModelBanner) setupModelBanner.classList.add('hidden');
      const shortName = activeModel.name;
      if (activeModelShortLabel) activeModelShortLabel.textContent = 'Ready';
      if (drawerModelPill) drawerModelPill.textContent = shortName;
      headerStatusText.textContent = activeModel.kind === 'cloud' ? 'Cloud · ' + shortName : 'On-device · ' + shortName;
    } else {
      if (setupModelBanner && isAndroidNative) setupModelBanner.classList.remove('hidden');
      if (activeModelShortLabel) activeModelShortLabel.textContent = 'Choose';
      headerStatusText.textContent = isAndroidNative ? 'Choose a model' : 'Browser preview';
    }
    headerStatusDot.dataset.ready = isReady;
  }

  // Hook for Native Android onResume
  window.updatePermissionStatus = function(isA11y, isOverlay) {
    refreshStatus();
  };

  // 6. Permission Button Handlers
  if (setupA11yBtn) {
    setupA11yBtn.addEventListener('click', () => {
      if (isAndroidNative) window.AndroidBridge.openAccessibilitySettings();
    });
  }

  if (openAppInfoBtn) {
    openAppInfoBtn.addEventListener('click', () => {
      if (isAndroidNative) window.AndroidBridge.openAppInfoSettings();
    });
  }

  if (drawerA11yBtn) {
    drawerA11yBtn.addEventListener('click', () => {
      if (isAndroidNative) window.AndroidBridge.openAccessibilitySettings();
      closeDrawer();
    });
  }

  if (drawerAppInfoBtn) {
    drawerAppInfoBtn.addEventListener('click', () => {
      if (isAndroidNative) window.AndroidBridge.openAppInfoSettings();
      closeDrawer();
    });
  }

  if (drawerOverlayBtn) {
    drawerOverlayBtn.addEventListener('click', () => {
      if (isAndroidNative) {
        window.AndroidBridge.openOverlaySettings();
        window.AndroidBridge.startFloatingOverlay();
      }
      closeDrawer();
    });
  }

  // 7. Model Hub Modal Handlers
  function openModelModal() {
    modelModal.classList.remove('hidden');
    if (modelEngine.getSelectedModel().kind === 'cloud') tabCloudApiBtn.click();
    else tabOnDeviceBtn.click();
    closeModelModalBtn.focus();
    refreshStatus();
  }

  function closeModelModal() {
    modelModal.classList.add('hidden');
    refreshStatus();
  }

  if (setupModelBtn) setupModelBtn.addEventListener('click', openModelModal);
  if (drawerModelBtn) {
    drawerModelBtn.addEventListener('click', () => {
      closeDrawer();
      openModelModal();
    });
  }
  if (topStatusIndicator) topStatusIndicator.addEventListener('click', openModelModal);
  if (closeModelModalBtn) closeModelModalBtn.addEventListener('click', closeModelModal);
  if (doneModelModalBtn) doneModelModalBtn.addEventListener('click', closeModelModal);

  // Tab Switching inside Model Hub
  if (tabOnDeviceBtn && tabCloudApiBtn) {
    tabOnDeviceBtn.addEventListener('click', () => {
      tabOnDeviceBtn.className = 'flex-1 py-1.5 rounded-lg bg-[#333333] text-white transition-all';
      tabCloudApiBtn.className = 'flex-1 py-1.5 rounded-lg text-slate-400 hover:text-white transition-all';
      tabOnDeviceContent.classList.remove('hidden');
      tabCloudApiContent.classList.add('hidden');
    });

    tabCloudApiBtn.addEventListener('click', () => {
      tabCloudApiBtn.className = 'flex-1 py-1.5 rounded-lg bg-[#333333] text-white transition-all';
      tabOnDeviceBtn.className = 'flex-1 py-1.5 rounded-lg text-slate-400 hover:text-white transition-all';
      tabCloudApiContent.classList.remove('hidden');
      tabOnDeviceContent.classList.add('hidden');
    });
  }

  // Download Gemma 4 Action
  if (downloadGemmaBtn) {
    downloadGemmaBtn.addEventListener('click', () => {
      const gemmaModel = AVAILABLE_MODELS.find(m => m.id === 'gemma-4-2b-it');

      // CRITICAL: Prevent re-downloading if model is already downloaded!
      if (isAndroidNative && window.AndroidBridge.checkModelStatus) {
        try {
          const status = JSON.parse(window.AndroidBridge.checkModelStatus(gemmaModel.fileName) || '{}');
          if (status.installed) {
            modelEngine.setModel('gemma-4-2b-it');
            if (window.AndroidBridge.showToast) {
              window.AndroidBridge.showToast('Google Gemma 4 is already installed and active');
            }
            refreshStatus();
            closeModelModal();
            return;
          }
        } catch (e) {}
      }

      if (isAndroidNative && window.AndroidBridge.downloadModel) {
        if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Starting Download...';
        if (gemmaDownloadProgressBox) gemmaDownloadProgressBox.classList.remove('hidden');

        const downloadId = window.AndroidBridge.downloadModel(gemmaModel.downloadUrl, gemmaModel.fileName);
        if (downloadId > 0) {
          if (gemmaStatusBadge) {
            gemmaStatusBadge.textContent = 'Downloading';
            gemmaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-purple-900/60 text-purple-300 border border-purple-700/60 animate-pulse';
          }
        } else {
          if (gemmaProgressStatus) gemmaProgressStatus.textContent = 'FAILED: model entry not recognized by native layer';
          if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Retry Download Gemma 4 E2B';
          if (gemmaStatusBadge) gemmaStatusBadge.textContent = 'Failed';
        }
      } else {
        alert('Model download is supported on Android devices. In web demo, model is simulated.');
      }
    });
  }

  // Delete Gemma 4
  if (gemmaDeleteBtn) {
    gemmaDeleteBtn.addEventListener('click', () => {
      const gemmaModel = AVAILABLE_MODELS.find(m => m.id === 'gemma-4-2b-it');
      if (isAndroidNative && window.AndroidBridge.deleteModel) {
        window.AndroidBridge.deleteModel(gemmaModel.fileName);
        if (window.AndroidBridge.showToast) window.AndroidBridge.showToast('Gemma 4 model deleted');
        refreshStatus();
      }
    });
  }

  // Download Gemma 4 E4B Action
  if (downloadE4bBtn) {
    downloadE4bBtn.addEventListener('click', () => {
      const e4bModel = AVAILABLE_MODELS.find(m => m.id === 'gemma-4-4b-it');
      if (!e4bModel) return;

      // CRITICAL: Prevent re-downloading if model is already downloaded!
      if (isAndroidNative && window.AndroidBridge.checkModelStatus) {
        try {
          const status = JSON.parse(window.AndroidBridge.checkModelStatus(e4bModel.fileName) || '{}');
          if (status.installed) {
            modelEngine.setModel('gemma-4-4b-it');
            if (window.AndroidBridge.showToast) {
              window.AndroidBridge.showToast('Gemma 4 E4B is already installed and active');
            }
            refreshStatus();
            closeModelModal();
            return;
          }
        } catch (e) {}
      }

      if (isAndroidNative && window.AndroidBridge.downloadModel) {
        if (downloadE4bBtnText) downloadE4bBtnText.textContent = 'Starting Download...';
        if (e4bDownloadProgressBox) e4bDownloadProgressBox.classList.remove('hidden');

        const downloadId = window.AndroidBridge.downloadModel(e4bModel.downloadUrl, e4bModel.fileName);
        if (downloadId > 0) {
          if (e4bStatusBadge) {
            e4bStatusBadge.textContent = 'Downloading';
            e4bStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-indigo-900/60 text-indigo-300 border border-indigo-700/60 animate-pulse';
          }
        }
      }
    });
  }

  // Delete Gemma 4 E4B
  if (e4bDeleteBtn) {
    e4bDeleteBtn.addEventListener('click', () => {
      const e4bModel = AVAILABLE_MODELS.find(m => m.id === 'gemma-4-4b-it');
      if (e4bModel && isAndroidNative && window.AndroidBridge.deleteModel) {
        window.AndroidBridge.deleteModel(e4bModel.fileName);
        if (window.AndroidBridge.showToast) window.AndroidBridge.showToast('Gemma 4 E4B deleted');
        refreshStatus();
      }
    });
  }

  // Native Android Download Progress Callback
  window.onModelDownloadProgress = function(data) {
    const percent = data.bytesTotal > 0 ? Math.min(100, Math.round((data.bytesDownloaded / data.bytesTotal) * 100)) : 0;
    const mbSoFar = (data.bytesDownloaded / (1024 * 1024)).toFixed(1);
    const mbTotal = (data.bytesTotal / (1024 * 1024)).toFixed(1);

    if (data.fileName && data.fileName.includes('E4B')) {
      if (e4bDownloadProgressBox) e4bDownloadProgressBox.classList.remove('hidden');
      if (e4bProgressBar) e4bProgressBar.style.width = `${percent}%`;
      if (e4bProgressText) e4bProgressText.textContent = `${mbSoFar} MB / ${mbTotal} MB (${percent}%)`;
      if (e4bProgressStatus) e4bProgressStatus.textContent = data.status;

      if (data.status === 'SUCCESSFUL') {
        if (e4bStatusBadge) {
          e4bStatusBadge.textContent = 'Installed';
          e4bStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
        }
        if (downloadE4bBtnText) downloadE4bBtnText.textContent = 'Active Model (Installed)';
        modelEngine.setModel('gemma-4-4b-it');
        refreshStatus();
      } else if (data.status && data.status.indexOf('FAILED') === 0) {
        if (e4bStatusBadge) {
          e4bStatusBadge.textContent = 'Failed';
          e4bStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-rose-900/60 text-rose-300 border border-rose-700/60';
        }
        if (downloadE4bBtnText) downloadE4bBtnText.textContent = 'Retry Download Gemma 4 E4B';
      }
    } else if (data.fileName && data.fileName.includes('Qwen')) {
      const box = document.getElementById('qwenDownloadProgressBox');
      const bar = document.getElementById('qwenProgressBar');
      const txt = document.getElementById('qwenProgressText');
      const st = document.getElementById('qwenProgressStatus');
      const badge = document.getElementById('qwenStatusBadge');
      const btnText = document.getElementById('downloadQwenBtnText');
      if (downloadQwenBtn) downloadQwenBtn.disabled = !/^(SUCCESSFUL|FAILED)/.test(data.status);
      if (box) box.classList.remove('hidden');
      if (bar) bar.style.width = `${percent}%`;
      if (txt) txt.textContent = `${mbSoFar} MB / ${mbTotal} MB (${percent}%)`;
      if (st) st.textContent = data.status;
      if (data.status === 'SUCCESSFUL') {
        if (badge) badge.textContent = 'Installed';
        modelEngine.setModel('qwen-3.5-2b');
        if (btnText) btnText.textContent = 'Selected';
        refreshStatus();
      } else if (data.status && data.status.indexOf('FAILED') === 0) {
        if (badge) badge.textContent = 'Failed';
        if (btnText) btnText.textContent = 'Retry Download Qwen 3.5 2B';
      }
    } else if (data.fileName && (data.fileName.includes('E2B') || data.fileName.includes('gemma'))) {
      if (gemmaDownloadProgressBox) gemmaDownloadProgressBox.classList.remove('hidden');
      if (gemmaProgressBar) gemmaProgressBar.style.width = `${percent}%`;
      if (gemmaProgressText) gemmaProgressText.textContent = `${mbSoFar} MB / ${mbTotal} MB (${percent}%)`;
      if (gemmaProgressStatus) gemmaProgressStatus.textContent = data.status;

      if (data.status === 'SUCCESSFUL') {
        if (gemmaStatusBadge) {
          gemmaStatusBadge.textContent = 'Installed';
          gemmaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
        }
        if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Active Model (Installed)';
        modelEngine.setModel('gemma-4-2b-it');
        refreshStatus();
      } else if (data.status && data.status.indexOf('FAILED') === 0) {
        if (gemmaStatusBadge) {
          gemmaStatusBadge.textContent = 'Failed';
          gemmaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-rose-900/60 text-rose-300 border border-rose-700/60';
        }
        if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Retry Download Gemma 4 E2B';
      }
    }
  };

  saveGeminiKeyBtn?.addEventListener('click', () => {
    const key = geminiApiKeyInput.value.trim();
    if (!key && !modelEngine.geminiApiKey) {
      geminiApiKeyInput.focus();
      return;
    }
    if (key) modelEngine.setGeminiApiKey(key);
    geminiApiKeyInput.value = '';
    modelEngine.setModel('api-gemini');
    closeModelModal();
  });

  document.getElementById('deleteLegacyQwenBtn')?.addEventListener('click', () => {
    if (window.AndroidBridge.deleteModel('Qwen3.5-4B_mixed_int4.litertlm')) refreshStatus();
    else window.AndroidBridge.showToast('Could not remove the old Qwen download.');
  });
  const downloadQwenBtn = document.getElementById('downloadQwenBtn');
  downloadQwenBtn?.addEventListener('click', () => {
    if (!isAndroidNative) return;
    const model = AVAILABLE_MODELS.find(item => item.id === 'qwen-3.5-2b');
    if (modelEngine.isModelReady(model)) {
      modelEngine.setModel(model.id);
      closeModelModal();
    } else if (window.AndroidBridge.downloadModel(model.downloadUrl, model.fileName) > 0) {
      downloadQwenBtn.disabled = true;
      document.getElementById('downloadQwenBtnText').textContent = 'Starting download…';
    }
  });

  // 8. Stream Rendering Helpers (ChatGPT Mobile Style)
  let activeAssistantContainer = null;

  function addUserMessage(text) {
    homePromptSection.classList.add('hidden');
    document.getElementById('thoughtList').replaceChildren();

    const row = document.createElement('div');
    row.className = 'flex justify-end my-2';
    row.innerHTML = `
      <div class="user-bubble">
        ${escapeHtml(text)}
      </div>
    `;
    messageStream.appendChild(row);
    scrollToBottom();

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

  function appendThoughtToStream(stepIndex, thoughtText, backend) {
    const list = document.getElementById('thoughtList');
    const item = document.createElement('div');
    item.className = 'model-detail';
    const title = document.createElement('strong');
    title.textContent = stepIndex > 0 ? 'Step ' + stepIndex + ' · ' + backend : backend;
    const detail = document.createElement('p');
    detail.textContent = thoughtText;
    item.append(title, detail);
    list.appendChild(item);
    list.scrollTop = list.scrollHeight;
  }

  function openThoughtPanel() {
    document.getElementById('thoughtPanel').classList.replace('hidden', 'flex');
    document.getElementById('thoughtToggleBtn').setAttribute('aria-expanded', 'true');
    document.getElementById('closeThoughtBtn').focus();
  }

  function closeThoughtPanel() {
    const panel = document.getElementById('thoughtPanel');
    if (panel.classList.replace('flex', 'hidden')) {
      const toggle = document.getElementById('thoughtToggleBtn');
      toggle.setAttribute('aria-expanded', 'false');
      toggle.focus();
    }
  }

  const toolLabels = {
    open_app: 'Open app', tap_node: 'Tap control', tap: 'Tap screen', t: 'Enter text',
    scroll: 'Scroll', system_key: 'Navigate', get_device_info: 'Read device information',
    web_search: 'Search the web', fetch_url: 'Read web page', see_screen: 'Inspect screen',
    send_message: 'Send message', get_notifications: 'Read notifications'
  };

  function appendActionToStream(stepIndex, action) {
    if (!activeAssistantContainer) createAssistantSlot();
    const step = document.createElement('div');
    step.className = 'trace-step';
    step.dataset.step = stepIndex;
    const title = document.createElement('div');
    title.className = 'trace-title';
    title.textContent = stepIndex + ' · ' + (toolLabels[action.type] || action.type.replaceAll('_', ' '));
    const target = document.createElement('div');
    target.className = 'trace-target';
    target.textContent = action.label || action.payload || '';
    const outcome = document.createElement('div');
    outcome.className = 'trace-result';
    outcome.textContent = 'In progress';
    step.append(title, target, outcome);
    activeAssistantContainer.appendChild(step);
    scrollToBottom();
  }

  function appendVerificationToStream(stepIndex, verification) {
    const outcome = activeAssistantContainer?.querySelector('[data-step="' + stepIndex + '"] .trace-result');
    if (!outcome) return;
    outcome.dataset.outcome = verification.success ? 'success' : 'failed';
    outcome.textContent = (verification.success ? 'Verified · ' : 'Could not verify · ') + verification.message;
    scrollToBottom();
  }

  function appendCompletionToStream(res) {
    if (!activeAssistantContainer) return;

    const summary = document.createElement('div');
    summary.className = 'assistant-answer';
    const label = res.isConversational ? 'NovaClaw' : 'Task result';
    const body = renderMarkdown(cleanAnswer(res.summary || ''));
    summary.innerHTML = `<div class="assistant-label">${label}</div><div class="md-body">${body}</div>`;
    activeAssistantContainer.appendChild(summary);
    scrollToBottom();
  }

  /** Drop meta-commentary the small model sometimes emits instead of an answer. */
  function cleanAnswer(text) {
    let t = (text || '').trim();
    t = t.replace(/^(the user (asked|wants|is asking)[^\n]*\n)+/i, '');
    t = t.replace(/^(here is (the|a) (answer|result)[:\s]*)/i, '');
    t = t.replace(/^(let me (think|reason)[^\n]*\n)/i, '');
    return t.trim();
  }

  /** Small markdown renderer — escape first, then format. */
  function renderMarkdown(text) {
    let s = escapeHtml(text || '');
    const codeBlocks = [];
    s = s.replace(/```([\s\S]*?)```/g, (_, code) => {
      codeBlocks.push(`<pre><code>${code.trim()}</code></pre>`);
      return `\u0000CODE${codeBlocks.length - 1}\u0000`;
    });
    s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>');
    s = s.replace(/^###\s+(.+)$/gm, '<h3>$1</h3>');
    s = s.replace(/^##\s+(.+)$/gm, '<h2>$1</h2>');
    s = s.replace(/^#\s+(.+)$/gm, '<h1>$1</h1>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/\*([^*]+)\*/g, '<em>$1</em>');
    s = s.replace(/\[([^\]]+)\]\((https?:[^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');

    const lines = s.split('\n');
    let out = '';
    let listType = null;
    function closeList() { if (listType) { out += `</${listType}>`; listType = null; } }
    for (const line of lines) {
      const ul = line.match(/^\s*[-*•]\s+(.*)$/);
      const ol = line.match(/^\s*\d+[.)]\s+(.*)$/);
      if (ul || ol) {
        const wanted = ul ? 'ul' : 'ol';
        if (listType !== wanted) { closeList(); out += `<${wanted}>`; listType = wanted; }
        out += `<li>${(ul || ol)[1]}</li>`;
      } else {
        closeList();
        if (line.trim()) out += `<p>${line}</p>`;
      }
    }
    closeList();
    codeBlocks.forEach((block, i) => { out = out.replace(`\u0000CODE${i}\u0000`, block); });
    return out;
  }

  function appendErrorToStream(errMsg) {
    if (!activeAssistantContainer) createAssistantSlot();

    const err = document.createElement('div');
    err.className = 'task-error';
    const title = document.createElement('strong');
    title.textContent = 'Unable to continue';
    const message = document.createElement('div');
    message.textContent = errMsg;
    err.append(title, message);
    if (/Android Accessibility settings/.test(errMsg)) {
      const enable = document.createElement('button');
      enable.type = 'button';
      enable.className = 'setup-button';
      enable.textContent = 'Open Accessibility settings';
      enable.addEventListener('click', () => window.AndroidBridge.openAccessibilitySettings());
      err.appendChild(enable);
    } else if (/Model Hub/.test(errMsg)) {
      const hub = document.createElement('button');
      hub.type = 'button';
      hub.className = 'setup-button';
      hub.textContent = 'Open model hub';
      hub.addEventListener('click', openModelModal);
      err.appendChild(hub);
    }
    activeAssistantContainer.appendChild(err);
    scrollToBottom();
  }

  function scrollToBottom() {
    chatViewport.scrollTop = chatViewport.scrollHeight;
  }

  function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML.replaceAll('"', '&quot;');
  }

  const thoughtToggle = document.getElementById('thoughtToggleBtn');
  if (thoughtToggle) thoughtToggle.addEventListener('click', function () {
    const p = document.getElementById('thoughtPanel');
    if (!p) return;
    if (p.classList.contains('hidden')) openThoughtPanel(); else closeThoughtPanel();
  });
  const closeThought = document.getElementById('closeThoughtBtn');
  if (closeThought) closeThought.addEventListener('click', closeThoughtPanel);

  // 9. Agent Loop Controller
  const agent = new AgentLoop({
    modelEngine,
    onStateChange: (state) => {
      const labels = {
        OBSERVING: 'Reading the screen', LOADING_MODEL: 'Loading the on-device model',
        THINKING: 'Choosing the next step',
        ACTING: 'Running an action', SAFETY_CHECK: 'Waiting for your approval',
        STOPPING: 'Stopping…'
      };
      const running = state !== 'IDLE';
      executionStatusBar.classList.toggle('hidden', !running);
      sendActionBtn.disabled = running;
      topNewChatBtn.disabled = running;
      drawerNewChatBtn.disabled = running;
      stopExecutionBtn.disabled = state === 'STOPPING';
      agentStateLabel.textContent = labels[state] || 'Starting';
      if (!running) {
        stepCounterLabel.textContent = '';
        activeSafetyApprovalKey = null;
        safetyModal.classList.add('hidden');
      }
    },
    onStep: ({ stepIndex, appName }) => {
      stepCounterLabel.textContent = 'Step ' + stepIndex + (appName ? ' · ' + appName : '');
    },
    onThought: ({ stepIndex, thought, backend }) => {
      appendThoughtToStream(stepIndex, thought, backend);
    },
    onAction: ({ stepIndex, action }) => {
      appendActionToStream(stepIndex, action);
    },
    onVerification: ({ stepIndex, verification, preHash, postHash }) => {
      appendVerificationToStream(stepIndex, verification, preHash, postHash);
    },
    onSafetyPrompt: ({ safetyResult, action, stepIndex }) => {
      activeSafetyApprovalKey = safetyResult.approvalKey;
      safetyReasonText.textContent = safetyResult.reason;
      safetyTargetText.textContent = action.label || action.payload;
      safetyBlockBtn.focus();
      safetyModal.classList.remove('hidden');
    },
    onComplete: (res) => {
      appendCompletionToStream(res);
    },
    onStopped: () => {
      const step = activeAssistantContainer?.querySelector(".trace-result:not([data-outcome])");
      if (step) step.textContent = "Stopped before verification";
      appendCompletionToStream({ summary: "Stopped", isConversational: false });
    },
    onError: (err) => {
      appendErrorToStream(err);
    }
  });

  // 10. Chat Form Submission & Validation
  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const prompt = chatInput.value.trim();
    if (!prompt || agent.isRunning) return;

    chatInput.value = '';
    chatInput.blur();
    executeUserPrompt(prompt);
  });

  function executeUserPrompt(prompt) {
    if (agent.isRunning) return;

    addUserMessage(prompt);

    agent.start(prompt);
  }

  document.querySelectorAll('.example-prompt').forEach(button => {
    button.addEventListener('click', () => {
      chatInput.value = button.dataset.prompt;
      chatInput.focus();
    });
  });

  // Stop Button
  stopExecutionBtn.addEventListener('click', () => {
    agent.stop();
  });

  // Safety Confirmation Handlers
  safetyBlockBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    if (activeSafetyApprovalKey) agent.denySafety(activeSafetyApprovalKey);
  });

  safetyAllowBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    if (activeSafetyApprovalKey) {
      agent.resumeAfterSafetyApproval(activeSafetyApprovalKey);
      activeSafetyApprovalKey = null;
    }
  });

  // New Chat Buttons
  function resetConversation() {
    if (agent.isRunning) return;
    if (isAndroidNative && window.AndroidBridge && window.AndroidBridge.newConversation) {
      window.AndroidBridge.newConversation();
    }
    messageStream.replaceChildren();
    activeAssistantContainer = null;
    document.getElementById('thoughtList').replaceChildren();
    closeThoughtPanel();
    homePromptSection.classList.remove('hidden');
    refreshStatus();
    closeDrawer();
  }

  topNewChatBtn.addEventListener('click', resetConversation);
  drawerNewChatBtn.addEventListener('click', resetConversation);

  // Initial Status Refresh
  refreshStatus();
});
