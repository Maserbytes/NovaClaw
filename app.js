/**
 * NovaClaw Application Controller
 * Connects the UI to the Autonomous ReAct Agent Loop, Mobile Screen Simulator,
 * Set-of-Marks Visualizer, and Quad-Shield Safety Guardian.
 */

document.addEventListener('DOMContentLoaded', () => {
  // 1. Initialize Core Engines
  const pruner = new SemanticUIPruner({ viewportWidth: 360, viewportHeight: 740 });
  const safetyShield = new SafetyShield();
  const verifier = new ActionVerifier({ settleDelayMs: 350 });
  const modelEngine = new ModelEngine();

  // 2. Simulated Mobile Environment State Machine
  class MobileDeviceSimulator {
    constructor() {
      this.currentScreenKey = 'HOME_SCREEN';
      this.currentScreen = MOCK_APPS.HOME_SCREEN;
      this.activeConversationText = '';
      this.batterySaverEnabled = false;
    }

    getCurrentScreen() {
      return this.currentScreen;
    }

    async executeAction(action) {
      const rippleContainer = document.getElementById('touchRippleContainer');

      // 1. Show Visual Touch Ripple if target element has coordinates
      if (action.targetElement && action.targetElement.center && rippleContainer) {
        const ripple = document.createElement('div');
        ripple.className = 'touch-ripple';
        ripple.style.left = `${action.targetElement.center.x}px`;
        ripple.style.top = `${action.targetElement.center.y}px`;
        rippleContainer.appendChild(ripple);
        setTimeout(() => ripple.remove(), 600);
      }

      // 2. Update Environment State based on Action
      if (action.type === 'launch_app') {
        const pkg = (action.payload || '').toLowerCase();
        if (pkg.includes('whatsapp')) {
          this.currentScreenKey = 'WHATSAPP_CHAT_LIST';
          this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
        } else if (pkg.includes('ubereats')) {
          this.currentScreenKey = 'UBER_EATS_CATALOG';
          this.currentScreen = MOCK_APPS.UBER_EATS_CATALOG;
        } else if (pkg.includes('settings')) {
          this.currentScreenKey = 'SETTINGS_MAIN';
          this.currentScreen = MOCK_APPS.SETTINGS_MAIN;
        } else if (pkg.includes('bank') || pkg.includes('wallet')) {
          this.currentScreenKey = 'BANK_PROTECTED_APP';
          this.currentScreen = MOCK_APPS.BANK_PROTECTED_APP;
        }
      } else if (action.type === 'click') {
        const label = (action.targetElement?.label || '').toLowerCase();
        
        // Navigation within WhatsApp
        if (this.currentScreenKey === 'WHATSAPP_CHAT_LIST') {
          if (label.includes('sarah') || action.targetId === 7) {
            this.currentScreenKey = 'WHATSAPP_CHAT_CONVERSATION';
            this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_CONVERSATION;
          }
        } else if (this.currentScreenKey === 'WHATSAPP_CHAT_CONVERSATION') {
          if (label.includes('send') || action.targetId === 11) {
            // Append sent message to conversation
            this.currentScreen = {
              ...MOCK_APPS.WHATSAPP_CHAT_CONVERSATION,
              elements: [
                ...MOCK_APPS.WHATSAPP_CHAT_CONVERSATION.elements,
                {
                  id: 12,
                  type: 'text',
                  text: this.activeConversationText || "Hey! Are we still meeting at 5:00 PM?",
                  bounds: { x: 90, y: 350, width: 260, height: 42 }
                }
              ]
            };
          } else if (label.includes('back') || action.targetId === 1) {
            this.currentScreenKey = 'WHATSAPP_CHAT_LIST';
            this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
          }
        }

        // Navigation within UberEats
        else if (this.currentScreenKey === 'UBER_EATS_CATALOG') {
          if (label.includes('latte') || label.includes('coffee') || action.targetId === 6) {
            this.currentScreenKey = 'UBER_EATS_PRODUCT';
            this.currentScreen = MOCK_APPS.UBER_EATS_PRODUCT;
          }
        } else if (this.currentScreenKey === 'UBER_EATS_PRODUCT') {
          if (label.includes('add to cart') || action.targetId === 7) {
            this.currentScreen = {
              ...MOCK_APPS.UBER_EATS_PRODUCT,
              elements: [
                ...MOCK_APPS.UBER_EATS_PRODUCT.elements.filter(e => e.id !== 7),
                {
                  id: 7,
                  type: 'button',
                  text: '✓ Added to Cart ($5.45)',
                  bounds: { x: 20, y: 730, width: 350, height: 52 },
                  clickable: true
                }
              ]
            };
          }
        }

        // Navigation within Settings
        else if (this.currentScreenKey === 'SETTINGS_MAIN') {
          if (label.includes('battery') || action.targetId === 4) {
            this.currentScreenKey = 'SETTINGS_BATTERY';
            this.currentScreen = MOCK_APPS.SETTINGS_BATTERY;
          }
        } else if (this.currentScreenKey === 'SETTINGS_BATTERY') {
          if (label.includes('battery saver') || action.targetId === 5) {
            this.batterySaverEnabled = true;
            this.currentScreen = {
              ...MOCK_APPS.SETTINGS_BATTERY,
              elements: MOCK_APPS.SETTINGS_BATTERY.elements.map(el => {
                if (el.id === 5) {
                  return { ...el, checked: true, text: 'Battery Saver (Enabled • Active)' };
                }
                return el;
              })
            };
          }
        }
      } else if (action.type === 'type') {
        this.activeConversationText = action.payload;
      } else if (action.type === 'key_home') {
        this.currentScreenKey = 'HOME_SCREEN';
        this.currentScreen = MOCK_APPS.HOME_SCREEN;
      } else if (action.type === 'key_back') {
        if (this.currentScreenKey.includes('CONVERSATION')) {
          this.currentScreenKey = 'WHATSAPP_CHAT_LIST';
          this.currentScreen = MOCK_APPS.WHATSAPP_CHAT_LIST;
        } else {
          this.currentScreenKey = 'HOME_SCREEN';
          this.currentScreen = MOCK_APPS.HOME_SCREEN;
        }
      }

      // Re-render phone screen immediately
      renderPhoneScreen(this.currentScreen);
    }
  }

  const deviceSim = new MobileDeviceSimulator();

  // 3. UI Element References
  const chatFeed = document.getElementById('chatFeed');
  const messagesContainer = document.getElementById('messagesContainer');
  const heroCard = document.getElementById('heroCard');
  const chatForm = document.getElementById('chatForm');
  const promptInput = document.getElementById('promptInput');
  const submitBtn = document.getElementById('submitBtn');
  const emergencyStopBtn = document.getElementById('emergencyStopBtn');
  const executionStatusBar = document.getElementById('executionStatusBar');
  const agentStateText = document.getElementById('agentStateText');
  const agentStateDot = document.getElementById('agentStateDot');
  const currentStepNum = document.getElementById('currentStepNum');
  const toggleSomBtn = document.getElementById('toggleSomBtn');
  const mobileAppContainer = document.getElementById('mobileAppContainer');
  const somOverlayContainer = document.getElementById('somOverlayContainer');
  const currentScreenLabel = document.getElementById('currentScreenLabel');
  const modelSelector = document.getElementById('modelSelector');
  const hardwareBadge = document.getElementById('hardwareBadge');
  const safetyCenterBtn = document.getElementById('safetyCenterBtn');
  const safetyCenterModal = document.getElementById('safetyCenterModal');
  const closeSafetyCenterBtn = document.getElementById('closeSafetyCenterBtn');
  const safetyDoneBtn = document.getElementById('safetyDoneBtn');
  const modelManagerBtn = document.getElementById('modelManagerBtn');
  const modelHubModal = document.getElementById('modelHubModal');
  const closeModelHubBtn = document.getElementById('closeModelHubBtn');
  const modelHubDoneBtn = document.getElementById('modelHubDoneBtn');
  const resetDemoBtn = document.getElementById('resetDemoBtn');
  const floatingOverlayPill = document.getElementById('floatingOverlayPill');
  const floatingPillText = document.getElementById('floatingPillText');
  const safetyModal = document.getElementById('safetyModal');
  const safetyRiskBadge = document.getElementById('safetyRiskBadge');
  const safetyModalReason = document.getElementById('safetyModalReason');
  const safetyModalTarget = document.getElementById('safetyModalTarget');
  const safetyApproveBtn = document.getElementById('safetyApproveBtn');
  const safetyDenyBtn = document.getElementById('safetyDenyBtn');

  // State flags
  let showSetOfMarks = true;
  let activeSafetyApprovalKey = null;

  // 4. Render Mobile Screen Canvas
  function renderPhoneScreen(screenData) {
    if (!mobileAppContainer) return;
    currentScreenLabel.textContent = screenData.appName || screenData.packageName || 'Active App';

    let html = '';

    // Render based on current screen ID
    if (screenData.id === 'home_screen') {
      html = `
        <div class="p-5 flex flex-col h-full bg-gradient-to-b from-slate-100 to-slate-200">
          <div class="mt-4 mb-6">
            <h2 class="text-2xl font-bold text-slate-800">Monday, Nov 17</h2>
            <p class="text-sm font-medium text-slate-500">24°C • Sunny San Francisco</p>
          </div>
          
          <div class="bg-white/80 backdrop-blur rounded-2xl px-4 py-2.5 mb-6 flex items-center gap-2 border border-slate-200/80 shadow-sm text-xs text-slate-400">
            <span>🔍</span>
            <span>Search apps, contacts, web...</span>
          </div>

          <div class="grid grid-cols-4 gap-4 text-center">
            <div class="flex flex-col items-center">
              <div class="w-14 h-14 rounded-2xl bg-emerald-500 text-white flex items-center justify-center text-2xl shadow-md">
                💬
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">WhatsApp</span>
            </div>
            <div class="flex flex-col items-center">
              <div class="w-14 h-14 rounded-2xl bg-slate-900 text-white flex items-center justify-center text-2xl shadow-md">
                ☕
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">Uber Eats</span>
            </div>
            <div class="flex flex-col items-center">
              <div class="w-14 h-14 rounded-2xl bg-blue-500 text-white flex items-center justify-center text-2xl shadow-md">
                🌐
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">Chrome</span>
            </div>
            <div class="flex flex-col items-center">
              <div class="w-14 h-14 rounded-2xl bg-slate-600 text-white flex items-center justify-center text-2xl shadow-md">
                ⚙️
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">Settings</span>
            </div>

            <div class="flex flex-col items-center mt-2">
              <div class="w-14 h-14 rounded-2xl bg-rose-600 text-white flex items-center justify-center text-2xl shadow-md">
                🏦
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">Bank Wallet</span>
            </div>
            <div class="flex flex-col items-center mt-2">
              <div class="w-14 h-14 rounded-2xl bg-amber-500 text-white flex items-center justify-center text-2xl shadow-md">
                📷
              </div>
              <span class="text-[11px] font-medium text-slate-700 mt-1">Camera</span>
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'whatsapp_chat_list') {
      html = `
        <div class="flex flex-col h-full bg-white">
          <div class="bg-emerald-700 text-white p-4 pt-3 flex items-center justify-between shadow-sm">
            <span class="font-bold text-base">WhatsApp</span>
            <div class="flex gap-3 text-sm">
              <span>🔍</span>
              <span>⋮</span>
            </div>
          </div>
          <div class="flex border-b border-slate-200 text-xs font-semibold text-emerald-800 bg-emerald-50/50">
            <div class="flex-1 py-2 text-center border-b-2 border-emerald-700">CHATS</div>
            <div class="flex-1 py-2 text-center text-slate-500">UPDATES</div>
            <div class="flex-1 py-2 text-center text-slate-500">CALLS</div>
          </div>
          <div class="flex-1 overflow-y-auto divide-y divide-slate-100">
            <div class="p-3 flex items-center gap-3 hover:bg-slate-50 cursor-pointer">
              <div class="w-11 h-11 rounded-full bg-purple-200 text-purple-800 flex items-center justify-center font-bold text-sm">SJ</div>
              <div class="flex-1">
                <div class="flex justify-between items-baseline">
                  <h4 class="text-xs font-semibold text-slate-800">Sarah Jenkins</h4>
                  <span class="text-[10px] text-slate-400">15:42</span>
                </div>
                <p class="text-[11px] text-slate-500 truncate">Let's grab coffee later today!</p>
              </div>
            </div>
            <div class="p-3 flex items-center gap-3 hover:bg-slate-50">
              <div class="w-11 h-11 rounded-full bg-rose-200 text-rose-800 flex items-center justify-center font-bold text-sm">M</div>
              <div class="flex-1">
                <div class="flex justify-between items-baseline">
                  <h4 class="text-xs font-semibold text-slate-800">Mom</h4>
                  <span class="text-[10px] text-slate-400">14:10</span>
                </div>
                <p class="text-[11px] text-slate-500 truncate">Don't forget dinner at 8pm.</p>
              </div>
            </div>
            <div class="p-3 flex items-center gap-3 hover:bg-slate-50">
              <div class="w-11 h-11 rounded-full bg-blue-200 text-blue-800 flex items-center justify-center font-bold text-sm">ET</div>
              <div class="flex-1">
                <div class="flex justify-between items-baseline">
                  <h4 class="text-xs font-semibold text-slate-800">Engineering Team</h4>
                  <span class="text-[10px] text-slate-400">12:30</span>
                </div>
                <p class="text-[11px] text-slate-500 truncate">Antigravity v2 PR merged!</p>
              </div>
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'whatsapp_chat_conversation') {
      html = `
        <div class="flex flex-col h-full bg-[#EFEAE2]">
          <div class="bg-emerald-700 text-white p-3 flex items-center gap-2 shadow">
            <span class="text-sm cursor-pointer">←</span>
            <div class="w-8 h-8 rounded-full bg-purple-200 text-purple-800 flex items-center justify-center font-bold text-xs">SJ</div>
            <div class="flex-1">
              <div class="text-xs font-bold leading-tight">Sarah Jenkins</div>
              <div class="text-[10px] text-emerald-100">online</div>
            </div>
            <span class="text-xs">📞</span>
            <span class="text-xs">⋮</span>
          </div>

          <div class="flex-1 p-3 overflow-y-auto space-y-2 text-xs">
            <div class="bg-white p-2.5 rounded-lg rounded-tl-none max-w-[80%] shadow-sm text-slate-800">
              Hey, are you free this afternoon?
            </div>
            <div class="bg-[#D9FDD3] p-2.5 rounded-lg rounded-tr-none ml-auto max-w-[80%] shadow-sm text-slate-800">
              Yeah! Around 5 works great.
            </div>
            <div class="bg-white p-2.5 rounded-lg rounded-tl-none max-w-[80%] shadow-sm text-slate-800">
              Perfect, let's meet at Blue Bottle Cafe.
            </div>
            ${deviceSim.activeConversationText ? `
              <div class="bg-[#D9FDD3] p-2.5 rounded-lg rounded-tr-none ml-auto max-w-[80%] shadow-sm text-slate-800 animate-in fade-in duration-300">
                ${deviceSim.activeConversationText} <span class="text-[9px] text-emerald-800 ml-1">✓✓</span>
              </div>
            ` : ''}
          </div>

          <div class="p-2 bg-white/95 flex items-center gap-2 border-t border-slate-200">
            <div class="flex-1 bg-slate-100 rounded-full px-3 py-1.5 text-xs text-slate-600 flex items-center justify-between">
              <span>${deviceSim.activeConversationText || 'Type a message...'}</span>
              <span class="text-slate-400">📎</span>
            </div>
            <div class="w-8 h-8 rounded-full bg-emerald-600 text-white flex items-center justify-center text-xs shadow">
              ➤
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'uber_eats_catalog') {
      html = `
        <div class="flex flex-col h-full bg-white">
          <div class="p-3 border-b border-slate-100">
            <div class="text-[10px] text-slate-400 font-semibold uppercase">Deliver Now</div>
            <div class="text-xs font-bold text-slate-800 flex items-center gap-1">
              742 Evergreen Terr <span class="text-[10px]">▼</span>
            </div>
            <div class="mt-2 bg-slate-100 rounded-xl px-3 py-2 text-xs text-slate-400 flex items-center gap-2">
              <span>🔍</span> <span>Search food, groceries, drinks...</span>
            </div>
          </div>

          <div class="p-3 flex gap-2 overflow-x-auto text-xs font-semibold text-slate-600">
            <span class="px-3 py-1 rounded-full bg-slate-900 text-white">Coffee</span>
            <span class="px-3 py-1 rounded-full bg-slate-100">Bakery</span>
            <span class="px-3 py-1 rounded-full bg-slate-100">Burgers</span>
          </div>

          <div class="flex-1 overflow-y-auto p-3 space-y-3">
            <div class="p-3 rounded-2xl border border-slate-200 shadow-sm flex gap-3 cursor-pointer hover:border-slate-300">
              <div class="w-16 h-16 rounded-xl bg-amber-100 text-amber-900 flex items-center justify-center text-2xl">☕</div>
              <div class="flex-1">
                <h4 class="text-xs font-bold text-slate-800">Iced Vanilla Oat Latte</h4>
                <p class="text-[11px] text-slate-500">Starbucks • 15-25 min</p>
                <div class="text-xs font-semibold text-slate-900 mt-1">$5.45 • ★ 4.8 (2.4k)</div>
              </div>
            </div>

            <div class="p-3 rounded-2xl border border-slate-200 shadow-sm flex gap-3">
              <div class="w-16 h-16 rounded-xl bg-blue-100 text-blue-900 flex items-center justify-center text-2xl">🧊</div>
              <div class="flex-1">
                <h4 class="text-xs font-bold text-slate-800">Cold Brew Reserve</h4>
                <p class="text-[11px] text-slate-500">Blue Bottle • 20-30 min</p>
                <div class="text-xs font-semibold text-slate-900 mt-1">$4.95 • ★ 4.9 (850)</div>
              </div>
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'uber_eats_product') {
      html = `
        <div class="flex flex-col h-full bg-white">
          <div class="p-3 flex items-center justify-between border-b border-slate-100">
            <span class="text-sm cursor-pointer font-bold">←</span>
            <span class="text-xs font-bold text-slate-800">Item Details</span>
            <span class="text-xs">♡</span>
          </div>
          <div class="h-36 bg-amber-50 flex items-center justify-center text-5xl">☕</div>
          <div class="p-4 flex-1">
            <h3 class="text-base font-bold text-slate-900">Iced Vanilla Oat Latte</h3>
            <p class="text-sm font-semibold text-slate-800 mt-0.5">$5.45</p>
            <p class="text-xs text-slate-500 mt-2">
              Rich espresso poured over ice and creamy oatmilk with Madagascar vanilla syrup.
            </p>
            <div class="mt-4 pt-3 border-t border-slate-100 space-y-2">
              <label class="flex items-center justify-between text-xs text-slate-700">
                <span>Extra Ice</span>
                <input type="checkbox" class="rounded text-purple-600">
              </label>
              <label class="flex items-center justify-between text-xs text-slate-700">
                <span>Extra Shot Espresso (+$1.00)</span>
                <input type="checkbox" class="rounded text-purple-600">
              </label>
            </div>
          </div>
          <div class="p-3 border-t border-slate-100">
            <button class="w-full py-2.5 rounded-xl bg-slate-900 text-white font-semibold text-xs shadow-md">
              ${screenData.elements.find(e => e.id === 7)?.text || 'Add to Cart - $5.45'}
            </button>
          </div>
        </div>
      `;
    } else if (screenData.id === 'settings_main') {
      html = `
        <div class="flex flex-col h-full bg-slate-50 p-4">
          <h2 class="text-xl font-bold text-slate-900 mb-3">Settings</h2>
          <div class="bg-white rounded-2xl p-2.5 mb-4 text-xs text-slate-400 border border-slate-200 shadow-sm flex items-center gap-2">
            <span>🔍</span> <span>Search settings</span>
          </div>
          <div class="space-y-2 flex-1 overflow-y-auto">
            <div class="p-3 bg-white rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div class="flex items-center gap-2.5">
                <span class="text-base">📶</span>
                <div>
                  <div class="text-xs font-semibold text-slate-800">Network & Internet</div>
                  <div class="text-[10px] text-slate-500">Wi-Fi, Mobile Data</div>
                </div>
              </div>
              <span class="text-slate-400 text-xs">›</span>
            </div>

            <div class="p-3 bg-white rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between cursor-pointer hover:bg-slate-50">
              <div class="flex items-center gap-2.5">
                <span class="text-base">🔋</span>
                <div>
                  <div class="text-xs font-semibold text-slate-800">Battery</div>
                  <div class="text-[10px] text-slate-500">88% • More than 1 day remaining</div>
                </div>
              </div>
              <span class="text-slate-400 text-xs">›</span>
            </div>

            <div class="p-3 bg-white rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div class="flex items-center gap-2.5">
                <span class="text-base">💾</span>
                <div>
                  <div class="text-xs font-semibold text-slate-800">Storage</div>
                  <div class="text-[10px] text-slate-500">48 GB used of 256 GB</div>
                </div>
              </div>
              <span class="text-slate-400 text-xs">›</span>
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'settings_battery') {
      html = `
        <div class="flex flex-col h-full bg-slate-50">
          <div class="p-3 bg-white flex items-center gap-2 border-b border-slate-100">
            <span class="text-sm font-bold cursor-pointer">←</span>
            <span class="text-xs font-bold text-slate-800">Battery</span>
          </div>
          <div class="p-4 flex-1">
            <div class="text-center py-4 bg-white rounded-2xl border border-slate-200 shadow-sm mb-4">
              <div class="text-3xl font-extrabold text-slate-900">88%</div>
              <p class="text-xs text-slate-500 mt-1">Should last until 11:30 PM tomorrow</p>
            </div>

            <div class="p-3.5 bg-white rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between mb-3">
              <div>
                <div class="text-xs font-bold text-slate-800">Battery Saver</div>
                <div class="text-[10px] text-slate-500">Restricts background apps & sync</div>
              </div>
              <div class="w-10 h-6 rounded-full flex items-center p-0.5 cursor-pointer ${deviceSim.batterySaverEnabled ? 'bg-emerald-600 justify-end' : 'bg-slate-300 justify-start'}">
                <div class="w-5 h-5 rounded-full bg-white shadow-sm"></div>
              </div>
            </div>

            <div class="p-3.5 bg-white rounded-2xl border border-slate-200 shadow-sm flex items-center justify-between">
              <div>
                <div class="text-xs font-bold text-slate-800">Adaptive Battery</div>
                <div class="text-[10px] text-slate-500">Limits battery for rarely used apps</div>
              </div>
              <div class="w-10 h-6 rounded-full bg-emerald-600 flex items-center justify-end p-0.5">
                <div class="w-5 h-5 rounded-full bg-white shadow-sm"></div>
              </div>
            </div>
          </div>
        </div>
      `;
    } else if (screenData.id === 'bank_protected_app') {
      html = `
        <div class="flex flex-col h-full bg-slate-900 text-white p-4">
          <div class="flex justify-between items-center mb-6">
            <span class="text-xs font-bold uppercase tracking-wider text-rose-400">Vault Protected</span>
            <span class="text-xs">🔒 256-bit</span>
          </div>
          <div class="p-4 rounded-2xl bg-slate-800/80 border border-slate-700 mb-6">
            <span class="text-[11px] text-slate-400">Total Checking Balance</span>
            <div class="text-2xl font-black mt-1">$14,890.50</div>
          </div>

          <div class="space-y-3 flex-1">
            <button class="w-full py-3 px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-left flex justify-between">
              <span>Send Money to Contact</span>
              <span>→</span>
            </button>
            <button class="w-full py-3 px-4 rounded-xl bg-slate-800 hover:bg-slate-700 text-xs font-semibold text-left flex justify-between">
              <span>Transfer to External Account</span>
              <span>→</span>
            </button>
            <button class="w-full py-3 px-4 rounded-xl bg-rose-950/60 border border-rose-800/80 text-rose-300 text-xs font-semibold text-left flex justify-between">
              <span>Delete Bank Account & Wipe Data</span>
              <span>⚠️</span>
            </button>
          </div>
        </div>
      `;
    }

    mobileAppContainer.innerHTML = html;

    // Render Set-of-Marks Badges
    renderSetOfMarksOverlay(screenData);
  }

  // 5. Render Set-of-Marks (SoM) Coordinates Overlay
  function renderSetOfMarksOverlay(screenData) {
    if (!somOverlayContainer) return;
    somOverlayContainer.innerHTML = '';

    if (!showSetOfMarks) return;

    const pruned = pruner.prune(screenData, { packageName: screenData.packageName });
    pruned.elements.forEach(el => {
      const badge = document.createElement('div');
      badge.className = 'som-badge';
      badge.textContent = `[${el.id}]`;
      badge.style.left = `${el.center.x}px`;
      badge.style.top = `${el.center.y}px`;
      badge.title = `[${el.id}] ${el.type}: "${el.label}"`;
      somOverlayContainer.appendChild(badge);
    });
  }

  // 6. Agent Loop Callbacks & Event Dispatch
  const agent = new AgentLoop({
    pruner,
    safetyShield,
    verifier,
    modelEngine,
    environment: deviceSim,
    maxSteps: 15,

    onStateChange: (state) => {
      if (agentStateText) agentStateText.textContent = state;
      if (agentStateDot) {
        agentStateDot.className = state === 'ACTING' || state === 'VERIFYING' 
          ? 'status-dot-green' 
          : 'status-dot-blue';
      }
      if (floatingPillText) {
        floatingPillText.textContent = `NovaClaw: ${state}`;
      }
    },

    onStep: (data) => {
      executionStatusBar.classList.remove('hidden');
      emergencyStopBtn.classList.remove('hidden');
      submitBtn.classList.add('hidden');
      currentStepNum.textContent = data.stepIndex;
      renderPhoneScreen(data.screen);
    },

    onThought: (data) => {
      // Append thought block to active agent message
      appendThoughtToTimeline(data.stepIndex, data.thought, data.plan);
    },

    onAction: (data) => {
      appendActionToTimeline(data.stepIndex, data.action);
    },

    onVerification: (data) => {
      appendVerificationToTimeline(data.stepIndex, data.verification, data.preHash, data.postHash);
    },

    onSafetyPrompt: (promptData) => {
      activeSafetyApprovalKey = promptData.safetyResult.approvalKey;
      safetyRiskBadge.textContent = `Risk Level: ${promptData.safetyResult.riskLevel}`;
      safetyModalReason.textContent = promptData.safetyResult.reason;
      safetyModalTarget.textContent = promptData.safetyResult.promptText;
      safetyModal.classList.remove('hidden');
    },

    onComplete: (res) => {
      executionStatusBar.classList.add('hidden');
      emergencyStopBtn.classList.add('hidden');
      submitBtn.classList.remove('hidden');
      appendCompletionToTimeline(res);
    },

    onError: (err) => {
      executionStatusBar.classList.add('hidden');
      emergencyStopBtn.classList.add('hidden');
      submitBtn.classList.remove('hidden');
      appendErrorToTimeline(err);
    }
  });

  // 7. Timeline DOM Helpers (ChatGPT Aesthetic)
  let currentAgentMessageEl = null;
  let currentStepsContainerEl = null;

  function addUserMessage(prompt) {
    if (heroCard) heroCard.classList.add('hidden');

    const wrapper = document.createElement('div');
    wrapper.className = 'flex justify-end animate-in fade-in slide-in-from-bottom-2 duration-200';
    wrapper.innerHTML = `
      <div class="max-w-[80%] bg-slate-900 text-white text-xs font-medium px-4 py-2.5 rounded-2xl rounded-br-sm shadow-sm">
        ${escapeHtml(prompt)}
      </div>
    `;
    messagesContainer.appendChild(wrapper);
    chatFeed.scrollTop = chatFeed.scrollHeight;

    // Create container for agent response
    createAgentResponseContainer();
  }

  function createAgentResponseContainer() {
    const wrapper = document.createElement('div');
    wrapper.className = 'flex gap-3 animate-in fade-in slide-in-from-bottom-2 duration-300';
    wrapper.innerHTML = `
      <div class="w-7 h-7 rounded-xl bg-purple-600 text-white flex items-center justify-center font-bold text-xs shrink-0 shadow-sm shadow-purple-600/20">
        ✦
      </div>
      <div class="flex-1 space-y-2">
        <div class="agent-steps space-y-2"></div>
      </div>
    `;
    messagesContainer.appendChild(wrapper);
    currentAgentMessageEl = wrapper;
    currentStepsContainerEl = wrapper.querySelector('.agent-steps');
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function appendThoughtToTimeline(stepIndex, thoughtText, plan) {
    if (!currentStepsContainerEl) createAgentResponseContainer();

    const stepCard = document.createElement('div');
    stepCard.id = `agent-step-${stepIndex}`;
    stepCard.className = 'p-3 bg-white/90 rounded-2xl border border-slate-200/90 shadow-sm space-y-2';

    stepCard.innerHTML = `
      <div class="flex items-center justify-between text-xs">
        <div class="flex items-center gap-1.5 font-semibold text-slate-800">
          <span class="w-5 h-5 rounded-full bg-purple-100 text-purple-700 flex items-center justify-center text-[10px] font-bold">
            ${stepIndex}
          </span>
          <span>Tactical Sub-Goal</span>
        </div>
        <span class="text-[10px] text-slate-400 font-mono">Gemma 4 • LiteRT</span>
      </div>

      <div class="thought-card">
        <div class="font-medium text-slate-700 text-xs mb-1 flex items-center gap-1">
          <span class="text-purple-600">💭</span> Reasoning & Reflection
        </div>
        <div class="text-[12px] text-slate-600 leading-relaxed">
          ${escapeHtml(thoughtText)}
        </div>
      </div>

      <div class="action-slot pt-1"></div>
      <div class="verification-slot"></div>
    `;

    currentStepsContainerEl.appendChild(stepCard);
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function appendActionToTimeline(stepIndex, action) {
    const stepCard = document.getElementById(`agent-step-${stepIndex}`);
    if (!stepCard) return;

    const actionSlot = stepCard.querySelector('.action-slot');
    let badgeClass = 'action-badge-click';
    let icon = '👆';

    if (action.type === 'type') {
      badgeClass = 'action-badge-type';
      icon = '⌨️';
    } else if (action.type === 'launch_app') {
      badgeClass = 'action-badge-launch';
      icon = '🚀';
    }

    actionSlot.innerHTML = `
      <div class="flex items-center gap-2 text-xs">
        <span class="action-badge ${badgeClass}">
          ${icon} ${action.type}
        </span>
        <span class="font-medium text-slate-700 truncate">
          ${action.targetId ? `Mark [${action.targetId}]` : ''} 
          ${action.label ? `"${action.label}"` : (action.payload || '')}
        </span>
      </div>
    `;
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function appendVerificationToTimeline(stepIndex, verification, preHash, postHash) {
    const stepCard = document.getElementById(`agent-step-${stepIndex}`);
    if (!stepCard) return;

    const verSlot = stepCard.querySelector('.verification-slot');
    const isSuccess = verification.success;

    verSlot.innerHTML = `
      <div class="mt-1.5 pt-1.5 border-t border-slate-100 flex items-start gap-1.5 text-[11px] ${isSuccess ? 'text-emerald-700' : 'text-amber-700'}">
        <span class="mt-0.5">${isSuccess ? '✓' : '⚠️'}</span>
        <div class="flex-1">
          <div class="font-medium">${verification.message}</div>
          <div class="text-[10px] text-slate-400 font-mono mt-0.5">
            Diff: ${preHash} → ${postHash} (${verification.status})
          </div>
        </div>
      </div>
    `;
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function appendCompletionToTimeline(res) {
    if (!currentStepsContainerEl) return;

    const summaryCard = document.createElement('div');
    summaryCard.className = 'p-3.5 bg-gradient-to-r from-emerald-50 to-teal-50 border border-emerald-200 rounded-2xl text-xs text-emerald-900 shadow-sm animate-in fade-in duration-300';
    summaryCard.innerHTML = `
      <div class="flex items-center gap-2 font-bold mb-1">
        <span>🎉</span> Task Completed & Verified
      </div>
      <p class="text-[11px] text-emerald-800 leading-relaxed">
        ${res.summary}
      </p>
    `;
    currentStepsContainerEl.appendChild(summaryCard);
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function appendErrorToTimeline(err) {
    if (!currentStepsContainerEl) return;

    const errorCard = document.createElement('div');
    errorCard.className = 'p-3 bg-rose-50 border border-rose-200 rounded-2xl text-xs text-rose-800 shadow-sm';
    errorCard.innerHTML = `
      <div class="font-bold flex items-center gap-1.5 mb-0.5">
        <span>🛑</span> Execution Paused
      </div>
      <p class="text-[11px]">${err}</p>
    `;
    currentStepsContainerEl.appendChild(errorCard);
    chatFeed.scrollTop = chatFeed.scrollHeight;
  }

  function escapeHtml(str) {
    return String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  // 8. Event Handlers
  chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const prompt = promptInput.value.trim();
    if (!prompt) return;

    addUserMessage(prompt);
    promptInput.value = '';
    agent.start(prompt);
  });

  // Preset Buttons Click
  document.querySelectorAll('.preset-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const prompt = btn.getAttribute('data-prompt');
      if (prompt) {
        addUserMessage(prompt);
        agent.start(prompt);
      }
    });
  });

  // Emergency Stop Buttons
  emergencyStopBtn.addEventListener('click', () => {
    agent.stop();
  });

  floatingOverlayPill.addEventListener('click', () => {
    agent.stop();
  });

  // Set-of-Marks Toggle
  toggleSomBtn.addEventListener('click', () => {
    showSetOfMarks = !showSetOfMarks;
    toggleSomBtn.className = showSetOfMarks
      ? 'px-2.5 py-1.5 rounded-xl text-xs font-semibold flex items-center gap-1 transition-all bg-purple-50 text-purple-700 border border-purple-200'
      : 'px-2.5 py-1.5 rounded-xl text-xs font-semibold flex items-center gap-1 transition-all bg-slate-100 text-slate-500 border border-slate-200';
    renderSetOfMarksOverlay(deviceSim.getCurrentScreen());
  });

  // Model Selector
  modelSelector.addEventListener('change', (e) => {
    const selected = modelEngine.setModel(e.target.value);
    hardwareBadge.textContent = `${selected.recommendedNPU} (${modelEngine.tokensPerSecond} tok/s)`;
  });

  // Reset Environment
  resetDemoBtn.addEventListener('click', () => {
    agent.stop();
    deviceSim.currentScreenKey = 'HOME_SCREEN';
    deviceSim.currentScreen = MOCK_APPS.HOME_SCREEN;
    deviceSim.activeConversationText = '';
    deviceSim.batterySaverEnabled = false;
    renderPhoneScreen(deviceSim.getCurrentScreen());
    if (messagesContainer) messagesContainer.innerHTML = '';
    if (heroCard) heroCard.classList.remove('hidden');
    executionStatusBar.classList.add('hidden');
    emergencyStopBtn.classList.add('hidden');
    submitBtn.classList.remove('hidden');
  });

  // Android Navigation Controls
  document.getElementById('androidHomeBtn')?.addEventListener('click', () => {
    deviceSim.executeAction({ type: 'key_home' });
  });

  document.getElementById('androidBackBtn')?.addEventListener('click', () => {
    deviceSim.executeAction({ type: 'key_back' });
  });

  // Safety Confirmation Modal Handlers
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
    appendErrorToTimeline('User denied authorization for sensitive action.');
  });

  // Safety Center & Model Hub Modals
  safetyCenterBtn.addEventListener('click', () => safetyCenterModal.classList.remove('hidden'));
  closeSafetyCenterBtn.addEventListener('click', () => safetyCenterModal.classList.add('hidden'));
  safetyDoneBtn.addEventListener('click', () => safetyCenterModal.classList.add('hidden'));

  modelManagerBtn.addEventListener('click', () => modelHubModal.classList.remove('hidden'));
  closeModelHubBtn.addEventListener('click', () => modelHubModal.classList.add('hidden'));
  modelHubDoneBtn.addEventListener('click', () => modelHubModal.classList.add('hidden'));

  // Initial Render of Phone Screen
  renderPhoneScreen(deviceSim.getCurrentScreen());
});
