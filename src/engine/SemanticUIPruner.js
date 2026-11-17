/**
 * NovaClaw Semantic UI Pruner
 * 
 * Solves PokeClaw's #1 failure point: Context Overflow & Hallucination.
 * Raw Android Accessibility trees contain 400-800 redundant container nodes
 * (15,000+ tokens). This pruner filters, collapses, and normalizes the tree
 * into a compact Set-of-Marks (SoM) representation (~250 tokens) optimized
 * for on-device SLMs like Google Gemma 4 (2B/4B) and Llama 3.2.
 */

class SemanticUIPruner {
  constructor(options = {}) {
    this.viewportWidth = options.viewportWidth || 390;
    this.viewportHeight = options.viewportHeight || 844;
    this.minElementSize = options.minElementSize || 10;
  }

  /**
   * Prunes a raw accessibility tree or simulated DOM tree into an actionable element list.
   * @param {Object} rootNode - Root accessibility node
   * @param {Object} screenMeta - Metadata like packageName, currentActivity
   * @returns {Object} Pruned tree, element map, and LLM-ready prompt string
   */
  prune(rootNode, screenMeta = {}) {
    const interactiveElements = [];
    const elementMap = new Map();
    let currentId = 1;

    // Traverse and collect candidate elements
    this._traverse(rootNode, (node) => {
      if (!this._isCandidate(node)) return;

      const semanticText = this._resolveLabel(node);
      const isInteractive = this._isInteractive(node);

      // We prioritize interactive elements, but also include significant static text
      // that provides essential context (like headers, prices, error alerts).
      if (isInteractive || (semanticText && semanticText.length > 1 && node.bounds.height > 12)) {
        const center = {
          x: Math.round(node.bounds.x + node.bounds.width / 2),
          y: Math.round(node.bounds.y + node.bounds.height / 2)
        };

        const prunedElement = {
          id: currentId,
          type: this._classifyType(node),
          label: semanticText.trim(),
          packageName: screenMeta.packageName || node.packageName || 'unknown',
          bounds: { ...node.bounds },
          center: center,
          clickable: !!node.clickable,
          editable: !!node.editable,
          scrollable: !!node.scrollable,
          checkable: !!node.checkable,
          checked: !!node.checked,
          resourceId: node.resourceId || null
        };

        interactiveElements.push(prunedElement);
        elementMap.set(currentId, prunedElement);
        currentId++;
      }
    });

    // Deduplicate overlapping parent-child wrappers
    const dedupedElements = this._deduplicate(interactiveElements);

    // Generate compact prompt string for Gemma 4 / Llama 3.2
    const promptString = this._formatForLLM(dedupedElements, screenMeta);

    // Compute deterministic structural state hash for verification
    const stateHash = this._computeStateHash(dedupedElements, screenMeta);

    return {
      elements: dedupedElements,
      elementMap: new Map(dedupedElements.map(e => [e.id, e])),
      promptString,
      stateHash,
      elementCount: dedupedElements.length,
      packageName: screenMeta.packageName || 'system'
    };
  }

  _traverse(node, callback) {
    if (!node) return;
    callback(node);
    if (Array.isArray(node.children)) {
      for (const child of node.children) {
        this._traverse(child, callback);
      }
    } else if (Array.isArray(node.elements)) {
      for (const el of node.elements) {
        this._traverse(el, callback);
      }
    }
  }

  _isCandidate(node) {
    if (!node || !node.bounds) return false;
    // Filter out zero-size or off-screen elements
    const { x, y, width, height } = node.bounds;
    if (width < this.minElementSize || height < this.minElementSize) return false;
    if (x + width < 0 || y + height < 0) return false;
    if (x > this.viewportWidth || y > this.viewportHeight) return false;
    if (node.visible === false || node.isVisibleToUser === false) return false;
    return true;
  }

  _isInteractive(node) {
    return Boolean(
      node.clickable || 
      node.editable || 
      node.scrollable || 
      node.checkable ||
      node.focusable ||
      node.type === 'button' ||
      node.type === 'input' ||
      node.type === 'tab' ||
      node.type === 'switch'
    );
  }

  _resolveLabel(node) {
    if (node.text && node.text.trim()) return node.text.trim();
    if (node.contentDescription && node.contentDescription.trim()) return node.contentDescription.trim();
    if (node.placeholder && node.placeholder.trim()) return `[Input: ${node.placeholder.trim()}]`;
    if (node.hint && node.hint.trim()) return node.hint.trim();
    
    // Look into immediate children if this is a button or clickable card without direct text
    if (node.children && node.children.length > 0) {
      for (const child of node.children) {
        const childLabel = this._resolveLabel(child);
        if (childLabel) return childLabel;
      }
    }
    return '';
  }

  _classifyType(node) {
    const rawType = (node.className || node.type || '').toLowerCase();
    if (rawType.includes('edittext') || rawType.includes('input') || node.editable) return 'input';
    if (rawType.includes('button') || node.clickable) return 'button';
    if (rawType.includes('switch') || rawType.includes('checkbox') || rawType.includes('toggle')) return 'toggle';
    if (rawType.includes('tab')) return 'tab';
    if (rawType.includes('scroll') || rawType.includes('recycler') || rawType.includes('list')) return 'list';
    if (rawType.includes('image')) return 'image';
    return 'text';
  }

  _deduplicate(elements) {
    // If a parent and child share the same center and label, retain the more descriptive one
    const result = [];
    for (let i = 0; i < elements.length; i++) {
      const el = elements[i];
      let isDuplicate = false;
      for (let j = 0; j < result.length; j++) {
        const prev = result[j];
        const dist = Math.hypot(el.center.x - prev.center.x, el.center.y - prev.center.y);
        if (dist < 15 && (el.label === prev.label || !el.label || !prev.label)) {
          // Keep the one with better label or clickable property
          if (!prev.label && el.label) prev.label = el.label;
          if (el.clickable) prev.clickable = true;
          isDuplicate = true;
          break;
        }
      }
      if (!isDuplicate) {
        result.push(el);
      }
    }
    // Re-index clean sequential IDs: [1], [2], [3]...
    return result.map((item, idx) => ({ ...item, id: idx + 1 }));
  }

  _formatForLLM(elements, screenMeta) {
    const header = `[Current Screen: ${screenMeta.appName || screenMeta.packageName || 'Active App'} | Total Actions: ${elements.length}]\n`;
    const lines = elements.map(el => {
      const flags = [];
      if (el.clickable) flags.push('click');
      if (el.editable) flags.push('type');
      if (el.scrollable) flags.push('scroll');
      if (el.checked !== undefined && el.checkable) flags.push(el.checked ? 'ON' : 'OFF');

      const flagStr = flags.length > 0 ? ` [${flags.join(',')}]` : '';
      const labelStr = el.label ? ` "${el.label.slice(0, 45)}"` : ' [Icon/Empty]';
      return `[${el.id}] ${el.type.toUpperCase()}${labelStr}${flagStr} @ (${el.center.x},${el.center.y})`;
    });

    return header + lines.join('\n');
  }

  _computeStateHash(elements, screenMeta) {
    // Structural representation used to verify whether an action caused a state change
    const signature = `${screenMeta.packageName || ''}:${elements.map(e => `${e.id}:${e.label}:${e.center.x}:${e.center.y}`).join('|')}`;
    let hash = 0;
    for (let i = 0; i < signature.length; i++) {
      hash = ((hash << 5) - hash) + signature.charCodeAt(i);
      hash |= 0;
    }
    return '0x' + Math.abs(hash).toString(16).padStart(8, '0');
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { SemanticUIPruner };
}
