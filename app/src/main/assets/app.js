/**
 * NovaClaw Application Controller
 * Replicates the clean, minimalist ChatGPT Mobile UI with slide-out navigation drawer
 * and direct on-device model downloader & execution engine.
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
      this.simScreen = (typeof MOCK_APPS !== 'undefined') ? MOCK_APPS.HOME_SCREEN : { packageName: 'home', elements: [] };
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
          await new Promise(r => setTimeout(r, 1200));
        } else if (action.type === 'click' && action.targetElement?.center) {
          window.AndroidBridge.performTap(action.targetElement.center.x, action.targetElement.center.y);
          await new Promise(r => setTimeout(r, 750));
        } else if (action.type === 'scroll') {
          window.AndroidBridge.performScroll(action.payload || 'down');
          await new Promise(r => setTimeout(r, 850));
        } else if (action.type === 'type') {
          window.AndroidBridge.performText(action.targetId || 1, action.payload || '');
          await new Promise(r => setTimeout(r, 600));
        } else if (action.type === 'key_back') {
          window.AndroidBridge.performBack();
          await new Promise(r => setTimeout(r, 600));
        } else if (action.type === 'key_home') {
          window.AndroidBridge.performHome();
          await new Promise(r => setTimeout(r, 600));
        }
        return;
      }

      // Simulation fallback for web preview
      if (typeof MOCK_APPS === 'undefined') return;
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

  // Download Llama Controls
  const downloadLlamaBtn = document.getElementById('downloadLlamaBtn');
  const downloadLlamaBtnText = document.getElementById('downloadLlamaBtnText');
  const llamaDeleteBtn = document.getElementById('llamaDeleteBtn');
  const llamaStatusBadge = document.getElementById('llamaStatusBadge');
  const llamaDownloadProgressBox = document.getElementById('llamaDownloadProgressBox');
  const llamaProgressBar = document.getElementById('llamaProgressBar');
  const llamaProgressText = document.getElementById('llamaProgressText');
  const llamaProgressStatus = document.getElementById('llamaProgressStatus');

  // Gemini API Controls
  const geminiApiKeyInput = document.getElementById('geminiApiKeyInput');
  const saveGeminiKeyBtn = document.getElementById('saveGeminiKeyBtn');
  const geminiKeyStatus = document.getElementById('geminiKeyStatus');
  const geminiActiveBadge = document.getElementById('geminiActiveBadge');
  const ollamaEndpointInput = document.getElementById('ollamaEndpointInput');
  const saveOllamaBtn = document.getElementById('saveOllamaBtn');

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
    const isReady = modelEngine.isModelReady();
    const activeModel = modelEngine.getSelectedModel();

    if (isAndroidNative && window.AndroidBridge.checkModelStatus) {
      try {
        const gemmaModel = AVAILABLE_MODELS.find(m => m.id === 'gemma-4-2b-it');
        const gemmaStatus = JSON.parse(window.AndroidBridge.checkModelStatus(gemmaModel.fileName) || '{}');
        if (gemmaStatus.installed) {
          if (gemmaStatusBadge) {
            gemmaStatusBadge.textContent = 'Installed';
            gemmaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
          }
          if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = '✓ Active Model (Ready)';
          if (downloadGemmaBtn) {
            downloadGemmaBtn.className = 'w-full py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl flex items-center justify-center gap-1.5 shadow transition-all';
          }
          if (gemmaDeleteBtn) gemmaDeleteBtn.classList.remove('hidden');
          if (gemmaDownloadProgressBox) gemmaDownloadProgressBox.classList.add('hidden');
        } else {
          if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Download Gemma 4 (1.70 GB)';
          if (downloadGemmaBtn) {
            downloadGemmaBtn.className = 'w-full py-2 bg-purple-600 hover:bg-purple-500 text-white font-bold rounded-xl flex items-center justify-center gap-1.5 shadow transition-all';
          }
          if (gemmaDeleteBtn) gemmaDeleteBtn.classList.add('hidden');
        }

        const llamaModel = AVAILABLE_MODELS.find(m => m.id === 'llama-3.2-1b-mobile');
        const llamaStatus = JSON.parse(window.AndroidBridge.checkModelStatus(llamaModel.fileName) || '{}');
        if (llamaStatus.installed) {
          if (llamaStatusBadge) {
            llamaStatusBadge.textContent = 'Installed';
            llamaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
          }
          if (downloadLlamaBtnText) downloadLlamaBtnText.textContent = '✓ Active Model (Ready)';
          if (downloadLlamaBtn) {
            downloadLlamaBtn.className = 'w-full py-2 bg-emerald-600 hover:bg-emerald-500 text-white font-bold rounded-xl flex items-center justify-center gap-1.5 shadow transition-all';
          }
          if (llamaDeleteBtn) llamaDeleteBtn.classList.remove('hidden');
          if (llamaDownloadProgressBox) llamaDownloadProgressBox.classList.add('hidden');
          if (!gemmaStatus.installed) {
            modelEngine.setModel('llama-3.2-1b-mobile');
          }
        } else {
          if (downloadLlamaBtnText) downloadLlamaBtnText.textContent = 'Download Llama 3.2 (750 MB)';
          if (downloadLlamaBtn) {
            downloadLlamaBtn.className = 'w-full py-2 bg-[#333333] hover:bg-[#3D3D3D] text-slate-200 font-bold rounded-xl flex items-center justify-center gap-1.5 transition-all';
          }
          if (llamaDeleteBtn) llamaDeleteBtn.classList.add('hidden');
        }
      } catch (e) {
        console.warn('Model status check error:', e);
      }
    }

    if (modelEngine.geminiApiKey) {
      if (geminiKeyStatus) geminiKeyStatus.textContent = '✓ Real LLM Reasoning active with Google Gemini Flash';
      if (geminiActiveBadge) {
        geminiActiveBadge.textContent = 'Active (Real LLM)';
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
      const shortName = modelEngine.geminiApiKey ? 'Gemini Flash' : (activeModel.name.split(' ')[1] || 'Gemma');
      if (activeModelShortLabel) activeModelShortLabel.textContent = shortName;
      if (drawerModelPill) drawerModelPill.textContent = shortName;
      if (headerStatusText) headerStatusText.textContent = modelEngine.geminiApiKey ? 'NovaClaw (Real LLM)' : 'NovaClaw Ready';
      if (headerStatusDot) headerStatusDot.className = 'w-1.5 h-1.5 rounded-full bg-emerald-500';
    } else {
      if (setupModelBanner && isAndroidNative) setupModelBanner.classList.remove('hidden');
      if (activeModelShortLabel) activeModelShortLabel.textContent = 'Set Up LLM';
      if (headerStatusText) headerStatusText.textContent = 'Set Up LLM';
      if (headerStatusDot) headerStatusDot.className = 'w-1.5 h-1.5 rounded-full bg-amber-500 animate-pulse';
    }
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

  // Download Llama 3.2 Action
  if (downloadLlamaBtn) {
    downloadLlamaBtn.addEventListener('click', () => {
      const llamaModel = AVAILABLE_MODELS.find(m => m.id === 'llama-3.2-1b-mobile');

      // CRITICAL: Prevent re-downloading if model is already downloaded!
      if (isAndroidNative && window.AndroidBridge.checkModelStatus) {
        try {
          const status = JSON.parse(window.AndroidBridge.checkModelStatus(llamaModel.fileName) || '{}');
          if (status.installed) {
            modelEngine.setModel('llama-3.2-1b-mobile');
            if (window.AndroidBridge.showToast) {
              window.AndroidBridge.showToast('Meta Llama 3.2 is already installed and active');
            }
            refreshStatus();
            closeModelModal();
            return;
          }
        } catch (e) {}
      }

      if (isAndroidNative && window.AndroidBridge.downloadModel) {
        if (downloadLlamaBtnText) downloadLlamaBtnText.textContent = 'Starting Download...';
        if (llamaDownloadProgressBox) llamaDownloadProgressBox.classList.remove('hidden');

        const downloadId = window.AndroidBridge.downloadModel(llamaModel.downloadUrl, llamaModel.fileName);
        if (downloadId > 0) {
          if (llamaStatusBadge) {
            llamaStatusBadge.textContent = 'Downloading';
            llamaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-blue-900/60 text-blue-300 border border-blue-700/60 animate-pulse';
          }
        }
      }
    });
  }

  // Delete Llama 3.2
  if (llamaDeleteBtn) {
    llamaDeleteBtn.addEventListener('click', () => {
      const llamaModel = AVAILABLE_MODELS.find(m => m.id === 'llama-3.2-1b-mobile');
      if (isAndroidNative && window.AndroidBridge.deleteModel) {
        window.AndroidBridge.deleteModel(llamaModel.fileName);
        if (window.AndroidBridge.showToast) window.AndroidBridge.showToast('Llama 3.2 model deleted');
        refreshStatus();
      }
    });
  }

  // Native Android Download Progress Callback
  window.onModelDownloadProgress = function(data) {
    const percent = data.bytesTotal > 0 ? Math.min(100, Math.round((data.bytesDownloaded / data.bytesTotal) * 100)) : 0;
    const mbSoFar = (data.bytesDownloaded / (1024 * 1024)).toFixed(1);
    const mbTotal = (data.bytesTotal / (1024 * 1024)).toFixed(1);

    if (data.fileName && data.fileName.includes('gemma')) {
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
      } else if (data.status === 'FAILED') {
        if (gemmaStatusBadge) {
          gemmaStatusBadge.textContent = 'Failed';
          gemmaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-rose-900/60 text-rose-300 border border-rose-700/60';
        }
        if (downloadGemmaBtnText) downloadGemmaBtnText.textContent = 'Retry Download Gemma 4';
      }
    } else if (data.fileName && (data.fileName.includes('Llama') || data.fileName.includes('llama'))) {
      if (llamaDownloadProgressBox) llamaDownloadProgressBox.classList.remove('hidden');
      if (llamaProgressBar) llamaProgressBar.style.width = `${percent}%`;
      if (llamaProgressText) llamaProgressText.textContent = `${mbSoFar} MB / ${mbTotal} MB (${percent}%)`;
      if (llamaProgressStatus) llamaProgressStatus.textContent = data.status;

      if (data.status === 'SUCCESSFUL') {
        if (llamaStatusBadge) {
          llamaStatusBadge.textContent = 'Installed';
          llamaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-emerald-900/60 text-emerald-300 border border-emerald-700/60';
        }
        if (downloadLlamaBtnText) downloadLlamaBtnText.textContent = 'Active Model (Installed)';
        modelEngine.setModel('llama-3.2-1b-mobile');
        refreshStatus();
      } else if (data.status === 'FAILED') {
        if (llamaStatusBadge) {
          llamaStatusBadge.textContent = 'Failed';
          llamaStatusBadge.className = 'text-[10px] font-bold px-2 py-0.5 rounded-full bg-rose-900/60 text-rose-300 border border-rose-700/60';
        }
        if (downloadLlamaBtnText) downloadLlamaBtnText.textContent = 'Retry Download Llama';
      }
    }
  };

  // Save Cloud Gemini API Key
  if (saveGeminiKeyBtn && geminiApiKeyInput) {
    if (modelEngine.geminiApiKey) geminiApiKeyInput.value = modelEngine.geminiApiKey;

    saveGeminiKeyBtn.addEventListener('click', () => {
      const key = geminiApiKeyInput.value.trim();
      if (key) {
        modelEngine.setGeminiApiKey(key);
        modelEngine.setModel('api-gemini');
        if (geminiKeyStatus) geminiKeyStatus.textContent = '✓ Saved! Gemini Flash Active.';
        refreshStatus();
        closeModelModal();
      }
    });
  }

  // Save Ollama Endpoint
  if (saveOllamaBtn && ollamaEndpointInput) {
    saveOllamaBtn.addEventListener('click', () => {
      const endpoint = ollamaEndpointInput.value.trim();
      if (endpoint) {
        modelEngine.setOllamaEndpoint(endpoint);
        modelEngine.setModel('local-ollama');
        alert(`Connected to Ollama: ${endpoint}`);
        refreshStatus();
        closeModelModal();
      }
    });
  }

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

    const activeModel = modelEngine.getSelectedModel();
    const modelBadge = activeModel.id === 'api-gemini' ? 'Gemini Flash' : (activeModel.id === 'local-ollama' ? 'Ollama' : 'Gemma 4 Local');

    const block = document.createElement('div');
    block.id = `turn-step-${stepIndex}`;
    block.className = 'space-y-1 text-xs';

    block.innerHTML = `
      <div>
        <div class="thought-pill" onclick="this.nextElementSibling.classList.toggle('hidden')">
          <span>💭 Step ${stepIndex} Reasoning • <span class="text-purple-400 font-semibold">${modelBadge}</span></span>
          <span class="text-[9px]">▾</span>
        </div>
        <div class="thought-drawer">
          <div class="text-[10px] text-purple-300 font-mono mb-1.5 flex items-center gap-1.5">
            <span class="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse"></span>
            <span>[Local AI Engine: ${escapeHtml(activeModel.name)}]</span>
          </div>
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
    } else if (action.type === 'scroll') {
      tagClass = 'action-tag-click';
      icon = '📜';
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
      <div class="p-3 rounded-2xl bg-[#1C1C1E] border ${res.isConversational ? 'border-purple-800/60' : 'border-[#2C2C2E]'}">
        <div class="font-bold text-white flex items-center gap-1.5 mb-1">
          <span>${res.isConversational ? '💬' : '✨'}</span> ${res.isConversational ? 'NovaClaw' : 'Done'}
        </div>
        <div class="text-slate-300 leading-relaxed whitespace-pre-wrap">${escapeHtml(res.summary)}</div>
      </div>
    `;
    activeAssistantContainer.appendChild(summary);
    scrollToBottom();
  }

  function appendErrorToStream(errMsg) {
    if (!activeAssistantContainer) createAssistantSlot();

    const err = document.createElement('div');
    err.className = 'p-3 rounded-2xl bg-rose-950/40 border border-rose-900/60 text-xs text-rose-300 space-y-2';
    err.innerHTML = `
      <div class="font-bold flex items-center gap-1.5 text-rose-400">
        <span>⚠️</span> Action Halt
      </div>
      <div>${escapeHtml(errMsg)}</div>
    `;
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
    return div.innerHTML;
  }

  // 9. Agent Loop Controller
  const agent = new AgentLoop({
    pruner,
    safetyShield,
    verifier,
    modelEngine,
    environment: deviceEnv,
    maxSteps: Infinity,
    onStateChange: (state) => {
      if (state === 'IDLE') {
        executionStatusBar.classList.add('hidden');
      } else {
        executionStatusBar.classList.remove('hidden');
        agentStateLabel.textContent = `${state}...`;
      }
    },
    onStep: (step) => {
      const modelName = modelEngine.getSelectedModel().name.split(' ')[1] || 'Gemma 4';
      stepCounterLabel.textContent = `Step ${step.stepIndex} • ${modelName} Active`;
    },
    onThought: ({ stepIndex, thought, plan }) => {
      appendThoughtToStream(stepIndex, thought, plan);
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
      safetyTargetText.textContent = `Action: ${action.type} -> "${action.label || action.payload}"`;
      safetyModal.classList.remove('hidden');
    },
    onComplete: (res) => {
      appendCompletionToStream(res);
    },
    onError: (err) => {
      appendErrorToStream(err);
    }
  });

  // 10. Chat Form Submission & Validation
  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const prompt = chatInput.value.trim();
    if (!prompt) return;

    chatInput.value = '';
    executeUserPrompt(prompt);
  });

  function executeUserPrompt(prompt) {
    if (agent.isRunning) return;

    addUserMessage(prompt);

    // Permission Guard: If native and Accessibility is disabled, inform user immediately
    if (isAndroidNative) {
      const a11y = window.AndroidBridge.isAccessibilityEnabled();
      if (!a11y) {
        appendErrorToStream("Accessibility Service is currently disabled. NovaClaw cannot see or control your phone screen until permission is granted.\n\nPlease tap 'Enable' in the top banner (or open Phone Settings > Accessibility > NovaClaw AI). On Android 14, remember to tap 3-dots > 'Allow restricted settings' in App Info first if it is grayed out.");
        return;
      }
    }

    // Model Guard: If no model weights and no API key, prompt user to download
    if (!modelEngine.isModelReady()) {
      appendErrorToStream("No on-device AI model is installed yet. Please tap 'Install Gemma 4' in the top banner or side drawer to download model weights (1.45 GB) or configure a free Gemini API key for instant testing.");
      openModelModal();
      return;
    }

    // Execute Autonomous Agent ReAct Loop
    agent.start(prompt);
  }

  // Suggestion Buttons
  document.querySelectorAll('.chatgpt-suggestion-row, .chatgpt-suggestion-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const p = btn.getAttribute('data-prompt');
      if (p) {
        closeDrawer();
        executeUserPrompt(p);
      }
    });
  });

  // Stop Button
  stopExecutionBtn.addEventListener('click', () => {
    agent.stop();
  });

  // Safety Confirmation Handlers
  safetyBlockBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    agent.stop();
  });

  safetyAllowBtn.addEventListener('click', () => {
    safetyModal.classList.add('hidden');
    if (activeSafetyApprovalKey) {
      agent.resumeAfterSafetyApproval(activeSafetyApprovalKey);
    }
  });

  // New Chat Buttons
  function resetConversation() {
    agent.stop();
    if (isAndroidNative && window.AndroidBridge && window.AndroidBridge.newConversation) {
      window.AndroidBridge.newConversation();
    }
    messageStream.innerHTML = '';
    homePromptSection.classList.remove('hidden');
    refreshStatus();
    closeDrawer();
  }

  topNewChatBtn.addEventListener('click', resetConversation);
  drawerNewChatBtn.addEventListener('click', resetConversation);

  // Initial Status Refresh
  refreshStatus();
});
