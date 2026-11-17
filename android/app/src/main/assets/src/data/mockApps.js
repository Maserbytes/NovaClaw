/**
 * NovaClaw Simulated Mobile Environment & Screen Trees
 * 
 * Provides authentic Android view hierarchies with accessibility attributes,
 * bounding coordinates, and state machines for full interactive simulation.
 */

const MOCK_APPS = {
  HOME_SCREEN: {
    id: 'home_screen',
    packageName: 'com.android.launcher3',
    appName: 'OnePlus / Pixel Launcher',
    statusBar: { time: '16:20', battery: '88%', wifi: true, 5: '5G' },
    elements: [
      { id: 1, type: 'text', text: 'Monday, Nov 17', bounds: { x: 24, y: 80, width: 220, height: 28 }, clickable: false },
      { id: 2, type: 'text', text: '24°C Sunny', bounds: { x: 24, y: 112, width: 140, height: 24 }, clickable: false },
      { id: 3, type: 'input', placeholder: 'Search apps & web...', bounds: { x: 20, y: 160, width: 350, height: 50 }, clickable: true, editable: true },
      { id: 4, type: 'button', text: 'WhatsApp', bounds: { x: 30, y: 260, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 5, type: 'button', text: 'Uber Eats', bounds: { x: 120, y: 260, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 6, type: 'button', text: 'Chrome', bounds: { x: 210, y: 260, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 7, type: 'button', text: 'Settings', bounds: { x: 300, y: 260, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 8, type: 'button', text: 'Bank Wallet', bounds: { x: 30, y: 360, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 9, type: 'button', text: 'Camera', bounds: { x: 120, y: 360, width: 70, height: 75 }, clickable: true, className: 'android.widget.TextView' },
      { id: 10, type: 'button', text: 'Phone', bounds: { x: 35, y: 730, width: 60, height: 60 }, clickable: true },
      { id: 11, type: 'button', text: 'Messages', bounds: { x: 125, y: 730, width: 60, height: 60 }, clickable: true },
      { id: 12, type: 'button', text: 'Browser', bounds: { x: 215, y: 730, width: 60, height: 60 }, clickable: true },
      { id: 13, type: 'button', text: 'NovaClaw AI', bounds: { x: 305, y: 730, width: 60, height: 60 }, clickable: true }
    ]
  },

  WHATSAPP_CHAT_LIST: {
    id: 'whatsapp_chat_list',
    packageName: 'com.whatsapp',
    appName: 'WhatsApp Messenger',
    elements: [
      { id: 1, type: 'text', text: 'WhatsApp', bounds: { x: 20, y: 55, width: 140, height: 35 }, clickable: false },
      { id: 2, type: 'button', text: 'Search chats', contentDescription: 'Search', bounds: { x: 270, y: 55, width: 45, height: 40 }, clickable: true },
      { id: 3, type: 'button', text: 'More options', bounds: { x: 330, y: 55, width: 40, height: 40 }, clickable: true },
      { id: 4, type: 'tab', text: 'Chats', bounds: { x: 20, y: 105, width: 70, height: 35 }, clickable: true },
      { id: 5, type: 'tab', text: 'Updates', bounds: { x: 120, y: 105, width: 80, height: 35 }, clickable: true },
      { id: 6, type: 'tab', text: 'Calls', bounds: { x: 230, y: 105, width: 60, height: 35 }, clickable: true },
      // Chat items
      { id: 7, type: 'button', text: 'Sarah Jenkins: Let\'s grab coffee later today!', bounds: { x: 16, y: 160, width: 358, height: 65 }, clickable: true },
      { id: 8, type: 'button', text: 'Mom: Don\'t forget dinner at 8pm.', bounds: { x: 16, y: 235, width: 358, height: 65 }, clickable: true },
      { id: 9, type: 'button', text: 'Engineering Team: Antigravity v2 PR merged!', bounds: { x: 16, y: 310, width: 358, height: 65 }, clickable: true },
      { id: 10, type: 'button', text: 'Alex Turner: Send me the docs link please', bounds: { x: 16, y: 385, width: 358, height: 65 }, clickable: true },
      { id: 11, type: 'button', text: 'New Chat FAB', bounds: { x: 310, y: 710, width: 60, height: 60 }, clickable: true }
    ]
  },

  WHATSAPP_CHAT_CONVERSATION: {
    id: 'whatsapp_chat_conversation',
    packageName: 'com.whatsapp',
    appName: 'WhatsApp - Sarah Jenkins',
    elements: [
      { id: 1, type: 'button', text: 'Back button', bounds: { x: 15, y: 55, width: 35, height: 35 }, clickable: true },
      { id: 2, type: 'text', text: 'Sarah Jenkins', bounds: { x: 60, y: 50, width: 160, height: 25 }, clickable: false },
      { id: 3, type: 'text', text: 'online', bounds: { x: 60, y: 75, width: 80, height: 18 }, clickable: false },
      { id: 4, type: 'button', text: 'Voice Call', bounds: { x: 280, y: 55, width: 35, height: 35 }, clickable: true },
      { id: 5, type: 'button', text: 'Video Call', bounds: { x: 330, y: 55, width: 35, height: 35 }, clickable: true },
      // Messages
      { id: 6, type: 'text', text: 'Hey, are you free this afternoon?', bounds: { x: 40, y: 150, width: 260, height: 45 }, clickable: false },
      { id: 7, type: 'text', text: 'Yeah! Around 5 works great.', bounds: { x: 120, y: 215, width: 240, height: 45 }, clickable: false },
      { id: 8, type: 'text', text: 'Perfect, let\'s meet at Blue Bottle Cafe.', bounds: { x: 40, y: 280, width: 280, height: 45 }, clickable: false },
      // Input bar
      { id: 9, type: 'input', placeholder: 'Type a message', bounds: { x: 16, y: 730, width: 285, height: 48 }, clickable: true, editable: true },
      { id: 10, type: 'button', text: 'Attach File', bounds: { x: 260, y: 735, width: 35, height: 35 }, clickable: true },
      { id: 11, type: 'button', text: 'Send Message', bounds: { x: 315, y: 730, width: 50, height: 48 }, clickable: true }
    ]
  },

  UBER_EATS_CATALOG: {
    id: 'uber_eats_catalog',
    packageName: 'com.ubereats',
    appName: 'Uber Eats',
    elements: [
      { id: 1, type: 'text', text: 'Deliver to: 742 Evergreen Terr', bounds: { x: 20, y: 55, width: 240, height: 26 }, clickable: true },
      { id: 2, type: 'input', placeholder: 'Search food, groceries, drinks', bounds: { x: 20, y: 95, width: 350, height: 45 }, clickable: true, editable: true },
      { id: 3, type: 'button', text: 'Coffee & Tea', bounds: { x: 20, y: 155, width: 90, height: 40 }, clickable: true },
      { id: 4, type: 'button', text: 'Bakery', bounds: { x: 125, y: 155, width: 80, height: 40 }, clickable: true },
      { id: 5, type: 'button', text: 'Burgers', bounds: { x: 220, y: 155, width: 80, height: 40 }, clickable: true },
      // Coffee products
      { id: 6, type: 'button', text: 'Iced Vanilla Oat Latte - $5.45 (Starbucks)', bounds: { x: 20, y: 215, width: 350, height: 100 }, clickable: true },
      { id: 7, type: 'button', text: 'Cold Brew Reserve - $4.95 (Blue Bottle)', bounds: { x: 20, y: 330, width: 350, height: 100 }, clickable: true },
      { id: 8, type: 'button', text: 'Caramel Macchiato - $5.75 (Peet\'s)', bounds: { x: 20, y: 445, width: 350, height: 100 }, clickable: true },
      // Bottom navigation
      { id: 9, type: 'tab', text: 'Home', bounds: { x: 30, y: 745, width: 60, height: 35 }, clickable: true },
      { id: 10, type: 'tab', text: 'Browse', bounds: { x: 125, y: 745, width: 60, height: 35 }, clickable: true },
      { id: 11, type: 'tab', text: 'Baskets (0)', bounds: { x: 220, y: 745, width: 70, height: 35 }, clickable: true },
      { id: 12, type: 'tab', text: 'Account', bounds: { x: 305, y: 745, width: 60, height: 35 }, clickable: true }
    ]
  },

  UBER_EATS_PRODUCT: {
    id: 'uber_eats_product',
    packageName: 'com.ubereats',
    appName: 'Uber Eats - Iced Vanilla Oat Latte',
    elements: [
      { id: 1, type: 'button', text: 'Back to menu', bounds: { x: 20, y: 55, width: 36, height: 36 }, clickable: true },
      { id: 2, type: 'text', text: 'Iced Vanilla Oat Latte', bounds: { x: 20, y: 220, width: 280, height: 32 }, clickable: false },
      { id: 3, type: 'text', text: '$5.45 • 140 Cal', bounds: { x: 20, y: 255, width: 160, height: 24 }, clickable: false },
      { id: 4, type: 'text', text: 'Rich espresso poured over ice and creamy oatmilk with Madagascar vanilla syrup.', bounds: { x: 20, y: 290, width: 350, height: 50 }, clickable: false },
      { id: 5, type: 'switch', text: 'Extra Ice', checkable: true, checked: false, bounds: { x: 20, y: 360, width: 350, height: 40 }, clickable: true },
      { id: 6, type: 'switch', text: 'Extra Shot Espresso (+$1.00)', checkable: true, checked: false, bounds: { x: 20, y: 410, width: 350, height: 40 }, clickable: true },
      { id: 7, type: 'button', text: 'Add to Cart - $5.45', bounds: { x: 20, y: 730, width: 350, height: 52 }, clickable: true }
    ]
  },

  SETTINGS_MAIN: {
    id: 'settings_main',
    packageName: 'com.android.settings',
    appName: 'System Settings',
    elements: [
      { id: 1, type: 'text', text: 'Settings', bounds: { x: 24, y: 65, width: 140, height: 36 }, clickable: false },
      { id: 2, type: 'input', placeholder: 'Search settings', bounds: { x: 20, y: 110, width: 350, height: 48 }, clickable: true, editable: true },
      { id: 3, type: 'button', text: 'Network & Internet (Wi-Fi, Mobile, Hotspot)', bounds: { x: 20, y: 175, width: 350, height: 55 }, clickable: true },
      { id: 4, type: 'button', text: 'Battery (88% - More than 1 day remaining)', bounds: { x: 20, y: 240, width: 350, height: 55 }, clickable: true },
      { id: 5, type: 'button', text: 'Storage (48 GB used of 256 GB)', bounds: { x: 20, y: 305, width: 350, height: 55 }, clickable: true },
      { id: 6, type: 'button', text: 'Display (Dark theme, Adaptive brightness)', bounds: { x: 20, y: 370, width: 350, height: 55 }, clickable: true },
      { id: 7, type: 'button', text: 'Security & Privacy (Biometrics, Screen lock)', bounds: { x: 20, y: 435, width: 350, height: 55 }, clickable: true },
      { id: 8, type: 'button', text: 'System & Reset (Languages, Gestures, Backup)', bounds: { x: 20, y: 500, width: 350, height: 55 }, clickable: true }
    ]
  },

  SETTINGS_BATTERY: {
    id: 'settings_battery',
    packageName: 'com.android.settings',
    appName: 'Settings - Battery',
    elements: [
      { id: 1, type: 'button', text: 'Back button', bounds: { x: 20, y: 55, width: 35, height: 35 }, clickable: true },
      { id: 2, type: 'text', text: 'Battery', bounds: { x: 65, y: 58, width: 100, height: 30 }, clickable: false },
      { id: 3, type: 'text', text: '88% Charged', bounds: { x: 24, y: 120, width: 200, height: 40 }, clickable: false },
      { id: 4, type: 'text', text: 'Usage: Screen on 2 hr 14 min since full charge', bounds: { x: 24, y: 165, width: 320, height: 24 }, clickable: false },
      { id: 5, type: 'switch', text: 'Battery Saver (Turns on dark theme, restricts background sync)', checkable: true, checked: false, bounds: { x: 20, y: 220, width: 350, height: 60 }, clickable: true },
      { id: 6, type: 'button', text: 'Battery Usage by App', bounds: { x: 20, y: 295, width: 350, height: 50 }, clickable: true },
      { id: 7, type: 'switch', text: 'Adaptive Battery', checkable: true, checked: true, bounds: { x: 20, y: 360, width: 350, height: 50 }, clickable: true }
    ]
  },

  BANK_PROTECTED_APP: {
    id: 'bank_protected_app',
    packageName: 'com.finance.bankwallet',
    appName: 'Secure Bank Wallet',
    elements: [
      { id: 1, type: 'text', text: 'Account Balance: $14,890.50', bounds: { x: 24, y: 80, width: 300, height: 35 }, clickable: false },
      { id: 2, type: 'button', text: 'Send Money to Contact', bounds: { x: 20, y: 140, width: 350, height: 50 }, clickable: true },
      { id: 3, type: 'button', text: 'Transfer to External Account', bounds: { x: 20, y: 200, width: 350, height: 50 }, clickable: true },
      { id: 4, type: 'button', text: 'Delete Bank Account & Wipe Data', bounds: { x: 20, y: 280, width: 350, height: 50 }, clickable: true }
    ]
  }
};

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { MOCK_APPS };
}
