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
    nativeId: '',
    name: 'Gemini Flash (cloud — disabled)',
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
    this.ollamaEndpoint = 'http://localhost:11434';
    this.selectedModelId = options.modelId || 'gemma-4-2b-it';
    this.temperature = options.temperature || 0.2;
    this.isLoaded = true;
  }

  getSelectedModel() {
    return AVAILABLE_MODELS.find(m => m.id === this.selectedModelId) || AVAILABLE_MODELS[0];
  }

  getAvailableModels() { return AVAILABLE_MODELS; }
  getNativeModelId() { return this.getSelectedModel().nativeId || 'gemma4-e2b'; }

  setModel(modelId) {
    if (AVAILABLE_MODELS.some(m => m.id === modelId)) this.selectedModelId = modelId;
    return this.getSelectedModel();
  }

  setGeminiApiKey(key) { this.geminiApiKey = (key || '').trim(); }
  setOllamaEndpoint(url) { this.ollamaEndpoint = (url || '').trim(); }

  isModelReady() {
    if (!window.AndroidBridge || !window.AndroidBridge.checkModelStatus) return true; // web preview
    const model = this.getSelectedModel();
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
