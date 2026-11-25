/**
 * NovaClaw model catalog — native-backed.
 * The old generated version downloaded GGUF weights that LiteRT-LM cannot load.
 * This catalog points at real .litertlm models and asks the native layer for
 * installation state.
 */

const AVAILABLE_MODELS = [
  {
    id: 'gemma-4-2b-it',
    nativeId: 'gemma4-e2b',
    name: 'Google Gemma 4 E2B',
    runtime: 'LiteRT-LM',
    size: '2.58 GB',
    fileName: 'gemma-4-E2B-it.litertlm',
    downloadUrl: 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm',
    ramRequired: '8 GB RAM',
    contextWindow: 4096,
    badge: 'On-device local model',
    isLocal: true,
  },
  {
    id: 'gemma-4-4b-it',
    nativeId: 'gemma4-e4b',
    name: 'Google Gemma 4 E4B',
    runtime: 'LiteRT-LM',
    size: '3.65 GB',
    fileName: 'gemma-4-E4B-it.litertlm',
    downloadUrl: 'https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm',
    ramRequired: '12 GB RAM',
    contextWindow: 6144,
    badge: 'On-device local model',
    isLocal: true,
  },
  {
    id: 'qwen-3.5-2b',
    nativeId: 'qwen3.5-2b',
    name: 'Qwen 3.5 2B',
    runtime: 'LiteRT-LM',
    size: '2.12 GB',
    fileName: 'Qwen3.5-2B_int8.litertlm',
    downloadUrl: 'https://huggingface.co/litert-community/Qwen3.5-2B/resolve/main/Qwen3.5-2B_int8.litertlm',
    ramRequired: '8 GB RAM',
    contextWindow: 4096,
    badge: 'On-device local model',
    isLocal: true,
  },
  {
    id: 'llama-3.2-1b-mobile',
    nativeId: '',
    name: 'Llama 3.2 1B (unsupported)',
    runtime: 'GGUF — not supported by LiteRT-LM',
    size: '—',
    fileName: 'not-supported.gguf',
    downloadUrl: '',
    ramRequired: '—',
    contextWindow: 0,
    badge: 'Unsupported in this build',
    isLocal: false,
  },
  {
    id: 'api-gemini',
    nativeId: 'gemini-flash',
    kind: 'cloud',
    name: 'Google Gemini Flash (cloud)',
    runtime: 'Cloud REST',
    size: '0 MB',
    fileName: '',
    downloadUrl: '',
    ramRequired: '0 MB RAM',
    contextWindow: 0,
    badge: 'Cloud option',
    isLocal: false,
  },
  {
    id: 'local-ollama',
    nativeId: '',
    name: 'Ollama bridge (disabled)',
    runtime: 'HTTP',
    size: 'External',
    fileName: '',
    downloadUrl: '',
    ramRequired: '—',
    contextWindow: 0,
    badge: 'Local bridge',
    isLocal: false,
  },
];

class ModelEngine {
  constructor(options = {}) {
    this.geminiApiKey = '';
    if (window.AndroidBridge && window.AndroidBridge.hasCloudApiKey &&
        window.AndroidBridge.hasCloudApiKey()) {
      this.geminiApiKey = 'saved-in-native-storage';
    }
    this.ollamaEndpoint = 'http://localhost:11434';
    let savedModel;
    try { savedModel = window.localStorage.getItem('nova.selectedModel'); } catch (e) {}
    const supported = AVAILABLE_MODELS.filter(model => model.nativeId);
    this.selectedModelId = supported.find(model => model.id === (options.modelId || savedModel))?.id
      || supported.find(model => model.isLocal && this.isModelReady(model))?.id
      || (this.geminiApiKey ? 'api-gemini' : 'gemma-4-2b-it');
    this.temperature = options.temperature || 0.2;
    this.isLoaded = true;
  }

  getSelectedModel() {
    return AVAILABLE_MODELS.find(m => m.id === this.selectedModelId) || AVAILABLE_MODELS[0];
  }

  getAvailableModels() { return AVAILABLE_MODELS; }
  getNativeModelId() { return this.getSelectedModel().nativeId || 'gemma4-e2b'; }

  setModel(modelId) {
    if (AVAILABLE_MODELS.some(model => model.id === modelId && model.nativeId)) {
      this.selectedModelId = modelId;
      try { window.localStorage.setItem('nova.selectedModel', modelId); } catch (e) {}
    }
    return this.getSelectedModel();
  }

  setGeminiApiKey(key) {
    this.geminiApiKey = (key || '').trim();
    if (window.AndroidBridge && window.AndroidBridge.setCloudApiKey) {
      window.AndroidBridge.setCloudApiKey(this.geminiApiKey);
    }
  }
  setOllamaEndpoint(url) { this.ollamaEndpoint = (url || '').trim(); }

  isModelReady(model = this.getSelectedModel()) {
    if (!window.AndroidBridge || !window.AndroidBridge.checkModelStatus) return false;
    if (model.kind === 'cloud') return !!this.geminiApiKey;
    if (!model.nativeId) return false;
    try {
      const status = JSON.parse(window.AndroidBridge.checkModelStatus(model.fileName) || '{}');
      return !!status.installed;
    } catch (e) {
      return false;
    }
  }

  getBackendState() {
    if (!window.AndroidBridge || !window.AndroidBridge.getBackendState) return 'unavailable';
    return window.AndroidBridge.getBackendState();
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { ModelEngine, AVAILABLE_MODELS };
}
