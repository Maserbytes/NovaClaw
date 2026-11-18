// Generates a small offline utility stylesheet for the classes used by the NovaClaw UI.
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const classes = fs.readFileSync(path.join(dir, 'classes.txt'), 'utf8').split(/\r?\n/).map(s => s.trim()).filter(Boolean);

const spacing = {
  '0': 0, '0.5': 2, '1': 4, '1.5': 6, '2': 8, '2.5': 10, '3': 12, '3.5': 14,
  '4': 16, '5': 20, '6': 24, '8': 32, '9': 36, '10': 40, '12': 48, '16': 64, '0.2': 1,
};
const palette = {
  slate: { 200: '#e2e8f0', 300: '#cbd5e1', 400: '#94a3b8', 500: '#64748b', 800: '#1e293b' },
  purple: { 200: '#e9d5ff', 300: '#d8b4fe', 400: '#c084fc', 500: '#a855f7', 600: '#9333ea', 700: '#7e22ce', 800: '#6b21a8', 900: '#581c87', 950: '#3b0764' },
  emerald: { 300: '#6ee7b7', 400: '#34d399', 500: '#10b981', 600: '#059669', 700: '#047857', 900: '#065f46' },
  amber: { 200: '#fde68a', 300: '#fcd34d', 400: '#fbbf24', 500: '#f59e0b', 700: '#b45309', 800: '#92400e', 900: '#78350f', 950: '#451a03' },
  rose: { 300: '#fda4af', 400: '#fb7185', 600: '#e11d48', 700: '#be123c', 900: '#881337', 950: '#4c0519' },
  blue: { 300: '#93c5fd', 400: '#60a5fa', 600: '#2563eb', 700: '#1d4ed8', 900: '#1e3a8a' },
  black: '#000000', white: '#ffffff', transparent: 'transparent',
};
function color(spec) {
  // spec like slate-300, amber-900/60, [#1C1C1E]
  let opacity = null;
  let s = spec;
  const slash = s.lastIndexOf('/');
  if (slash > 0 && /^\d+$/.test(s.slice(slash + 1))) {
    opacity = parseInt(s.slice(slash + 1), 10) / 100;
    s = s.slice(0, slash);
  }
  let hex;
  const arb = s.match(/^\[#([0-9a-fA-F]{3,8})\]$/);
  if (arb) {
    hex = '#' + arb[1];
  } else {
    const parts = s.split('-');
    if (parts.length === 1 && palette[parts[0]] && typeof palette[parts[0]] === 'string') hex = palette[parts[0]];
    else if (parts.length === 2 && palette[parts[0]] && palette[parts[0]][parts[1]]) hex = palette[parts[0]][parts[1]];
    else return null;
  }
  if (opacity !== null && hex.startsWith('#') && hex.length === 7) {
    const a = Math.round(opacity * 255).toString(16).padStart(2, '0');
    hex = hex + a;
  }
  return hex;
}
function esc(c) {
  return c.replace(/([\[\]#/.])/g, '\\$1');
}
const out = [];
const unknown = [];
function rule(sel, body) { out.push(sel + '{' + body + '}'); }

function processClass(cls) {
  if (/^(action-row|action-tag|assistant-bubble|assistant-content|thought-drawer|thought-pill|user-bubble|verify-row|chatgpt-suggestion-row|chatgpt-suggestion-btn|group|animate-in|zoom-in-95)$/.test(cls)) return;

  // variants
  let variant = '';
  let base = cls;
  const vm = base.match(/^(hover|focus|focus-within|group-hover|selection):(.+)$/);
  if (vm) { variant = vm[1]; base = vm[2]; }
  const prefix = variant ? (variant === 'group-hover' ? '.group:hover &' :
    variant === 'focus-within' ? '&:focus-within' :
    variant === 'selection' ? '::selection' : '&:' + variant) : '&';

  function emit(body) {
    const sel = prefix === '&' ? '.' + esc(cls) : prefix.replace('&', '.' + esc(cls));
    rule(sel, body);
  }

  let m;
  if ((m = base.match(/^bg-(.+)$/))) { const c = color(m[1]); if (c) return emit(`background-color:${c}`); }
  if ((m = base.match(/^text-(.+)$/))) {
    const c = color(m[1]);
    if (c) return emit(`color:${c}`);
    if (m[1] === 'center' || m[1] === 'left') return emit(`text-align:${m[1]}`);
  }
  if ((m = base.match(/^border-(.+)$/))) {
    const c = color(m[1]);
    if (c) return emit(`border-color:${c}`);
  }
  if (base === 'border') return emit('border-width:1px;border-style:solid;border-color:#333333');
  if (base === 'border-t') return emit('border-top-width:1px;border-top-style:solid');
  if (base === 'border-b') return emit('border-bottom-width:1px;border-bottom-style:solid');
  if (base === 'border-r') return emit('border-right-width:1px;border-right-style:solid');
  if (base === 'focus\\:outline-none' || cls === 'focus:outline-none') return emit('outline:none');
  if (base === 'outline-none') return emit('outline:none');

  const spaceProps = {
    p: 'padding', px: 'padding-left;padding-right', py: 'padding-top;padding-bottom',
    pt: 'padding-top', pb: 'padding-bottom', pl: 'padding-left', pr: 'padding-right',
    m: 'margin', mx: 'margin-left;margin-right', my: 'margin-top;margin-bottom',
    mt: 'margin-top', mb: 'margin-bottom', ml: 'margin-left', mr: 'margin-right',
    gap: 'gap',
  };
  if ((m = base.match(/^(p|px|py|pt|pb|pl|pr|m|mx|my|mt|mb|ml|mr|gap)-(\d+(?:\.\d+)?)$/))) {
    const v = spacing[m[2]];
    if (v !== undefined) return emit(spaceProps[m[1]].split(';').map(p => `${p}:${v}px`).join(';'));
  }
  if ((m = base.match(/^space-y-(\d+(?:\.\d+)?)$/))) {
    const v = spacing[m[1]];
    if (v !== undefined) return rule('.' + esc(cls) + ' > * + *', `margin-top:${v}px`);
  }
  if ((m = base.match(/^(w|h)-(\d+(?:\.\d+)?)$/))) {
    const v = spacing[m[2]];
    if (v !== undefined) return emit(`${m[1] === 'w' ? 'width' : 'height'}:${v}px`);
  }
  if ((m = base.match(/^(w|h|max-h|max-w|min-w)-\[(\d+(?:\.\d+)?)(px|vh|vw)\]$/))) {
    return emit(`${({ w: 'width', h: 'height', 'max-h': 'max-height', 'max-w': 'max-width', 'min-w': 'min-width' })[m[1]]}:${m[2]}${m[3]}`);
  }
  if (base === 'w-full') return emit('width:100%');
  if (base === 'h-full') return emit('height:100%');
  if (base === 'min-h-screen') return emit('min-height:100vh');
  if (base === 'min-w-0') return emit('min-width:0');
  if (base === 'max-w-sm') return emit('max-width:24rem');
  if (base === 'max-w-xl') return emit('max-width:36rem');

  const radius = { rounded: '4px', 'rounded-lg': '8px', 'rounded-xl': '12px', 'rounded-2xl': '16px', 'rounded-3xl': '24px', 'rounded-full': '9999px' };
  if (radius[base]) return emit(`border-radius:${radius[base]}`);

  const fontSizes = { 'text-[9px]': '9px', 'text-[10px]': '10px', 'text-[11px]': '11px', 'text-xs': '12px', 'text-sm': '14px', 'text-base': '16px', 'text-lg': '18px', 'text-xl': '20px', 'text-2xl': '24px', 'text-3xl': '30px' };
  if (base === 'sm:text-3xl') return rule('@media (min-width:640px){.sm\\:text-3xl}', 'font-size:30px');
  if (fontSizes[base]) return emit(`font-size:${fontSizes[base]}`);
  const weights = { 'font-light': '300', 'font-normal': '400', 'font-medium': '500', 'font-semibold': '600', 'font-bold': '700' };
  if (weights[base]) return emit(`font-weight:${weights[base]}`);
  if (base === 'font-mono') return emit('font-family:ui-monospace,SFMono-Regular,Menlo,monospace');
  if (base === 'leading-relaxed') return emit('line-height:1.625');
  if (base === 'tracking-tight') return emit('letter-spacing:-0.025em');
  if (base === 'tracking-wider') return emit('letter-spacing:0.05em');
  if (base === 'uppercase') return emit('text-transform:uppercase');
  if (base === 'underline') return emit('text-decoration:underline');
  if (base === 'truncate') return emit('overflow:hidden;text-overflow:ellipsis;white-space:nowrap');
  if (base === 'whitespace-pre-wrap') return emit('white-space:pre-wrap');

  const simples = {
    flex: 'display:flex', block: 'display:block', hidden: 'display:none',
    'flex-col': 'flex-direction:column', 'flex-1': 'flex:1 1 0%', 'shrink-0': 'flex-shrink:0',
    'items-center': 'align-items:center', 'items-start': 'align-items:flex-start',
    'justify-between': 'justify-content:space-between', 'justify-center': 'justify-content:center',
    'justify-end': 'justify-content:flex-end', fixed: 'position:fixed', sticky: 'position:sticky',
    'inset-0': 'inset:0', 'top-0': 'top:0', 'left-0': 'left:0', 'bottom-0': 'bottom:0',
    'overflow-hidden': 'overflow:hidden', 'overflow-x-hidden': 'overflow-x:hidden',
    'overflow-y-auto': 'overflow-y:auto', 'mx-auto': 'margin-left:auto;margin-right:auto',
    'my-2': 'margin-top:8px;margin-bottom:8px', 'antialiased': '-webkit-font-smoothing:antialiased',
    'cursor-pointer': 'cursor:pointer', 'animate-pulse': 'animation:nova-pulse 2s cubic-bezier(.4,0,.6,1) infinite',
    'backdrop-blur-sm': 'backdrop-filter:blur(4px)', 'opacity-90': 'opacity:0.9',
    'transition-all': 'transition:all .15s ease', 'transition-opacity': 'transition:opacity .3s ease',
    'transition-transform': 'transition:transform .3s ease', 'duration-150': 'transition-duration:150ms',
    'duration-300': 'transition-duration:300ms', 'ease-out': 'transition-timing-function:cubic-bezier(0,0,.2,1)',
    shadow: 'box-shadow:0 1px 3px rgba(0,0,0,.3)', 'shadow-sm': 'box-shadow:0 1px 2px rgba(0,0,0,.3)',
    'shadow-md': 'box-shadow:0 4px 6px rgba(0,0,0,.3)', 'shadow-lg': 'box-shadow:0 10px 15px rgba(0,0,0,.4)',
    'shadow-2xl': 'box-shadow:0 25px 50px rgba(0,0,0,.5)',
    'z-20': 'z-index:20', 'z-40': 'z-index:40', 'z-50': 'z-index:50',
    'placeholder-slate-400': 'color:#94a3b8', 'placeholder-slate-500': 'color:#64748b',
  };
  if (simples[base]) return emit(simples[base]);
  if (base === '-translate-x-full') return emit('transform:translateX(-100%)');
  if (base === 'translate-x-full') return emit('transform:translateX(100%)');
  unknown.push(cls);
}
classes.forEach(processClass);

const css = [
  '/* Generated offline utility stylesheet for NovaClaw — replaces Tailwind CDN */',
  'button,input,textarea{font:inherit;color:inherit;background:transparent;border:0;margin:0}',
  'button{cursor:pointer;-webkit-appearance:none;appearance:none}',
  '*{box-sizing:border-box}',
  '@keyframes nova-pulse{0%,100%{opacity:1}50%{opacity:.5}}',
  ...out,
  unknown.length ? '/* Unhandled: ' + unknown.join(', ') + ' */' : '',
].join('\n');
fs.writeFileSync(path.join(dir, 'tailwind-lite.css'), css);
console.log('rules=' + out.length + ' unknown=' + unknown.length);
if (unknown.length) console.log(unknown.join(', '));
