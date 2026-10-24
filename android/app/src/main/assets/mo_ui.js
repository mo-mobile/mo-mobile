/*
 * What Mo Mobile adds to the Mo page (loaded by MainActivity after each page):
 *  - the fixed "Mo Mobile" header: printer (print queue) on the left, settings
 *    gear on the right
 *  - windows roll up when you tap their title (arrow on the right shows which)
 *  - no dimmed windows
 *  - the page dots under each swipeable row
 *  - Sharing: while it's on, your own prints go into the same queue as
 *    friends' prints, and the printer opens that queue
 *
 * The friend page (app/static/js/share-friend.js) draws the same header and
 * windows; keep the two in step.
 */
(function () {
  if (window.__moMobileUi || !window.MoPrinter || !MoPrinter.openSettings) return;
  window.__moMobileUi = true;
  var real = window.MoPrinter;
  var share = window.MoShare;

  var GEAR = '<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.3-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65A.488.488 0 0 0 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1c-.23-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.09.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65zM12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z"/></svg>';
  var PRINTER = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/><rect x="6" y="14" width="12" height="8" rx="1"/></svg>';
  var PRINTER_X = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12.531 22H7a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h6.377"/><path d="m16.5 16.5 5 5"/><path d="m16.5 21.5 5-5"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.5"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/></svg>';
  var PRINTER_CHECK = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M13.5 22H7a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v.5"/><path d="m16 19 2 2 4-4"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v2"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/></svg>';
  var BACK = '<svg viewBox="0 0 24 24" width="100%" height="100%" aria-hidden="true"><path fill="currentColor" d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>';
  var CHEV = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m18 15-6-6-6 6"/></svg>';
  var X = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';
  var TRASH = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 11v6"/><path d="M14 11v6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M3 6h18"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';

  var css = document.createElement('style');
  css.textContent = [
    'body{padding-top:52px!important}',
    '.mh{position:fixed;left:0;right:0;top:0;z-index:50;display:flex;align-items:center;justify-content:space-between;font-family:Chicago7,sans-serif;color:#000}',
    '.mh .t{font-size:19px;font-weight:bold}',
    '.mh .ib{width:40px;height:40px;display:flex;align-items:center;justify-content:center;position:relative;background:none;border:0;padding:0;color:#000}',
    '.mh.B{height:44px;padding:0 10px;background:repeating-linear-gradient(to bottom,#6b6b6b 0,#6b6b6b 1px,#f8f8f8 2px,#f8f8f8 3px);border-bottom:2px solid #000}',
    '.mh.B .t{position:absolute;left:50%;transform:translateX(-50%);background:#f8f8f8;padding:0 12px}',
    '.mh.B .ib{background:#f8f8f8;width:36px;height:30px}',
    '.mh .ib svg{flex:none}',
    '.mm-badge{position:absolute;top:0;right:-4px;min-width:15px;height:15px;padding:0 4px;box-sizing:border-box;border-radius:8px;background:#000;color:#fff;font-size:11px;line-height:15px;text-align:center;font-family:Chicago7,sans-serif}',
    '.window.mm-shaded > :not(.title-bar){display:none!important}',
    '.window.mm-shaded{padding-bottom:0!important}',
    '.window > .title-bar{cursor:pointer;position:relative}',
    '.window > .title-bar .mm-chev{position:absolute;right:1.4rem;top:50%;transform:translateY(-50%);z-index:2;width:24px;height:22px;padding:0 3px;background:var(--gray1)!important;display:flex;align-items:center;justify-content:center}',
    '.window > .title-bar .mm-chev svg{width:18px;height:18px;transition:transform .15s}',
    '.window.mm-shaded > .title-bar .mm-chev svg{transform:rotate(180deg)}',
    '.window{border-color:#000!important;--black:#000!important}',
    '.window > .title-bar{background:repeating-linear-gradient(to bottom,var(--gray4) 0,var(--gray4) 1px,var(--gray1) 2px,var(--gray1) 3px)!important;color:#000!important;border-color:var(--gray4)!important}',
    '.window > .title-bar > span{background:var(--gray1)!important;color:#000!important}',
    // modals: same as Mo's setup page (dimmed backdrop, centered window)
    '.ms-modal{position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center}',
    '.ms-modal .window{width:calc(100% - 56px);max-width:360px;margin:0;max-height:calc(100% - 80px);display:flex;flex-direction:column}',
    '.ms-modal .title-bar .mm-bar-button{background:var(--gray1)}',
    '.ms-body{padding:12px 16px 16px;background:#fff;overflow:auto}',
    '.ms-lbl{font-size:11px;letter-spacing:.5px;margin:10px 0 6px}',
    '.ms-q{list-style:none;padding:0;margin:0}',
    '.ms-q li{display:flex;align-items:center;gap:12px;padding:9px 0;border-bottom:1px dotted #6b6b6b}',
    '.ms-q li:last-child{border-bottom:0}',
    '.ms-thumb{width:46px;height:64px;border:1px solid #111;background:#fff;flex:none;object-fit:cover}',
    '.ms-grow{flex:1;min-width:0}.ms-what{font-size:15px}.ms-who{font-size:12px;color:#444;margin-top:2px}',
    '.ms-bar{height:10px;border:1px solid #111;background:#fff;margin-top:6px;overflow:hidden}',
    '.ms-bar i{display:block;height:100%;width:200%;background:repeating-linear-gradient(-45deg,#111 0 3px,#fff 3px 6px);animation:ms-run 1.2s linear infinite}',
    '@keyframes ms-run{from{transform:translateX(-12px)}to{transform:translateX(0)}}',
    '.ms-x{width:36px;height:36px;border:2px solid #111;border-radius:.4rem;background:#fff;box-shadow:inset -.1rem -.1rem 0 #c8c8c8;display:flex;align-items:center;justify-content:center;padding:0;color:#111}',
    '.ms-x svg{width:18px;height:18px}',
    '.ms-clear{display:flex;align-items:center;justify-content:center;gap:8px;margin-top:14px}',
    '.ms-empty{font-size:14px;color:#444;padding:8px 0}',
    '.ms-toast{position:fixed;left:16px;right:16px;bottom:22px;z-index:1100;background:#fff;border:2px solid #000;box-shadow:3px 3px 0 #000;padding:10px 12px;font-size:14px;text-align:center}',
  ].join('');
  document.head.appendChild(css);

  // ----------------------------------------------------------- printing

  // Remember which print Mo just asked for; printBytes only gets the bytes.
  var lastPath = '';
  var realFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var path = url.replace(location.origin, '');
    if (path.indexOf('/api/print-data/') === 0 && path.indexOf('/api/print-data/decklist') !== 0) lastPath = path;
    return realFetch(input, init);
  };

  function shareState() {
    try { return JSON.parse(share.state()); } catch (e) { return { sharing: false, waiting: 0 }; }
  }

  // While Sharing is on, your prints join the queue (in order with friends').
  var wrapped = {};
  ['status', 'backgroundOptions', 'setBackgroundOption', 'downloadStarted', 'keepScreenOn', 'openSettings', 'choosePrinter']
    .forEach(function (name) {
      if (typeof real[name] === 'function') wrapped[name] = function () { return real[name].apply(real, arguments); };
    });
  wrapped.printBytes = function (b64) {
    if (share && shareState().sharing) {
      var result = share.printJob(b64, lastPath);
      if (result === 'ok') toast('Added to the print queue.');
      return result;
    }
    return real.printBytes(b64);
  };
  window.MoPrinter = wrapped;

  // --------------------------------------------------------------- header

  var header = document.createElement('div');
  header.className = 'mh B';
  header.innerHTML = '<button type="button" class="ib" id="mm-queue" aria-label="Print queue">' + PRINTER + '</button>' +
    '<div class="t">Mo Mobile</div>' +
    '<button type="button" class="ib" id="mm-settings" aria-label="Settings">' + GEAR + '</button>';
  document.body.appendChild(header);
  var queueButton = header.querySelector('#mm-queue');
  header.querySelector('#mm-settings').addEventListener('click', function () { real.openSettings(); });
  var onSetup = location.pathname === '/setup';
  // No Sharing server in this build: no print queue button (unless it's the back button).
  if (!onSetup && (!share || !shareState().available)) { queueButton.style.visibility = 'hidden'; share = null; }
  queueButton.addEventListener('click', function () {
    if (onSetup) { if (history.length > 1) history.back(); else real.openSettings(); return; }
    if (share && shareState().sharing) openQueue(); else if (share) share.openSharing();
  });
  // On the card download page the left button goes back instead.
  if (onSetup) {
    queueButton.setAttribute('aria-label', 'Back');
    queueButton.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path fill="currentColor" d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>';
  }

  var shownOn = 0, iconKey = 'printer';
  function setIcon(svg, key) {
    if (iconKey === key) return;
    iconKey = key;
    queueButton.querySelector('svg').outerHTML = svg;
  }

  function updateHeader() {
    if (!share || onSetup) return;
    var s = shareState();
    if (s.sharing && !shownOn) shownOn = Date.now();
    if (!s.sharing) shownOn = 0;
    if (!s.sharing) setIcon(PRINTER_X, 'x');
    else if (Date.now() - shownOn < 4000) setIcon(PRINTER_CHECK, 'check');
    else setIcon(PRINTER, 'printer');
    var badge = queueButton.querySelector('.mm-badge');
    var n = s.sharing ? s.waiting : 0;
    if (!n) { if (badge) badge.remove(); return; }
    if (!badge) { badge = document.createElement('span'); badge.className = 'mm-badge'; queueButton.appendChild(badge); }
    badge.textContent = n > 99 ? '99' : String(n);
  }
  updateHeader();
  setInterval(function () { if (!document.hidden) updateHeader(); }, 1500);

  // -------------------------------------------------------- roll-up windows

  [].slice.call(document.querySelectorAll('.window > .title-bar')).forEach(function (t) {
    var c = document.createElement('span');
    c.className = 'mm-chev';
    c.innerHTML = CHEV;
    t.appendChild(c);
    t.addEventListener('click', function () { t.parentElement.classList.toggle('mm-shaded'); });
  });

  // ----------------------------------------------------------- page dots

  // One set of dots over the bottom of each swipeable row of windows (Momir /
  // Stonehewer / Jhoira, and Deck Print / Top 8). It stays put while the
  // windows slide underneath; the filled dot follows the swipe, and tapping a
  // dot jumps to that window.
  [].slice.call(document.querySelectorAll('.window-row')).forEach(function (row) {
    var wins = [].slice.call(row.children).filter(function (w) { return w.classList.contains('window'); });
    if (wins.length < 2) return;
    wins.forEach(function (w) { w.style.paddingBottom = '12px'; });
    var host = row.parentElement;
    if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
    var dots = document.createElement('div');
    dots.className = 'mm-dots';
    dots.style.cssText = 'position:absolute;z-index:3;display:flex;justify-content:center;gap:10px;pointer-events:none;';
    var buttons = wins.map(function (_, j) {
      var d = document.createElement('button');
      d.type = 'button';
      d.setAttribute('aria-label', 'Window ' + (j + 1) + ' of ' + wins.length);
      // A 24px touch area around a 9px dot.
      d.style.cssText = 'pointer-events:auto;width:24px;height:24px;padding:0;margin:0 -7px;border:0;background:none;cursor:pointer;' +
        'display:flex;align-items:center;justify-content:center;';
      var dot = document.createElement('span');
      dot.style.cssText = 'display:block;width:9px;height:9px;box-sizing:border-box;border:1.5px solid #000;border-radius:50%;background:var(--gray1, #fff);';
      d.appendChild(dot);
      d.addEventListener('click', function (e) {
        e.preventDefault();
        row.scrollTo({ left: wins[j].offsetLeft - row.offsetLeft, behavior: 'smooth' });
      });
      dots.appendChild(d);
      return d;
    });
    host.appendChild(dots);
    function place() {
      dots.style.left = row.offsetLeft + 'px';
      dots.style.width = row.offsetWidth + 'px';
      dots.style.top = (row.offsetTop + row.offsetHeight - 29) + 'px';
    }
    function mark() {
      var i = Math.round(row.scrollLeft / Math.max(1, row.clientWidth));
      buttons.forEach(function (d, j) { d.firstChild.style.background = i === j ? '#000' : 'var(--gray1, #fff)'; });
    }
    place(); mark();
    row.addEventListener('scroll', mark, { passive: true });
    window.addEventListener('resize', function () { place(); mark(); });
    // Windows change height (a card shows up, etc.); keep the dots at the bottom.
    if (window.ResizeObserver) new ResizeObserver(place).observe(row);
  });

  // ------------------------------------------------------------ the queue

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // Same box button as the header's, inside a title bar.
  function barButton(bar, svg, label, onTap) {
    bar.style.position = 'relative';
    var title = bar.querySelector('span');
    var h = title ? title.getBoundingClientRect().height : bar.clientHeight;
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'mm-bar-button';
    b.setAttribute('aria-label', label);
    b.innerHTML = svg;
    b.style.cssText = 'position:absolute;left:1.4rem;top:50%;transform:translateY(-50%);z-index:2;' +
      'width:' + h + 'px;height:' + h + 'px;padding:' + Math.round(h * 0.16) + 'px;margin:0;box-sizing:border-box;' +
      'display:flex;align-items:center;justify-content:center;color:#000;border:0;border-radius:0;';
    b.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); onTap(); });
    bar.appendChild(b);
  }

  var queueOpen = false, queue = [];

  function openQueue() { queueOpen = true; renderQueue(); share.requestQueue(); }
  function closeQueue() { queueOpen = false; var el = document.getElementById('ms-queue'); if (el) el.remove(); }

  // The app calls this with the server's answer.
  window.__moShareQueue = function (body) {
    if (body && body.queue) queue = body.queue;
    renderQueue();
  };

  function renderQueue() {
    if (!queueOpen) return;
    var now = queue.filter(function (j) { return j.state === 'printing'; });
    var next = queue.filter(function (j) { return j.state !== 'printing'; });
    function item(j) {
      var thumb = j.thumb ? '<img class="ms-thumb" alt="" src="' + esc(j.thumb) + '">' : '<span class="ms-thumb"></span>';
      return '<li>' + thumb + '<div class="ms-grow"><div class="ms-what">' + esc(j.label) + '</div>' +
        '<div class="ms-who">' + esc(j.name) + '</div>' +
        (j.state === 'printing' ? '<div class="ms-bar"><i></i></div>' : '') + '</div>' +
        (j.canCancel ? '<button type="button" class="ms-x" data-cancel="' + j.id + '" aria-label="Cancel">' + X + '</button>' : '') +
        '</li>';
    }
    var html = '';
    if (!queue.length) html = '<div class="ms-empty">Nothing in the queue.</div>';
    if (now.length) html += '<div class="ms-lbl">NOW PRINTING</div><ul class="ms-q">' + now.map(item).join('') + '</ul>';
    if (next.length) {
      html += '<div class="ms-lbl">UP NEXT · ' + next.length + '</div><ul class="ms-q">' + next.map(item).join('') + '</ul>' +
        '<button type="button" class="action-btn primary ms-clear" id="ms-clear">' + TRASH + '<span>Clear all waiting</span></button>';
    }
    var el = document.getElementById('ms-queue');
    if (!el) {
      el = document.createElement('div');
      el.id = 'ms-queue';
      el.className = 'ms-modal';
      el.addEventListener('click', function (e) { if (e.target === el) closeQueue(); });
      document.body.appendChild(el);
    }
    el.innerHTML = '<div class="window is-active-window"><div class="title-bar"><span>Print Queue</span></div>' +
      '<div class="ms-body">' + html + '</div></div>';
    barButton(el.querySelector('.title-bar'), BACK, 'Back', closeQueue);
    [].slice.call(el.querySelectorAll('[data-cancel]')).forEach(function (b) {
      b.addEventListener('click', function () { b.disabled = true; share.cancel(Number(b.getAttribute('data-cancel'))); });
    });
    var clear = el.querySelector('#ms-clear');
    if (clear) clear.addEventListener('click', function () { clear.disabled = true; share.clear(); });
  }

  setInterval(function () { if (queueOpen && !document.hidden) share.requestQueue(); }, 3000);

  var toastTimer;
  function toast(text) {
    var el = document.querySelector('.ms-toast');
    if (!el) { el = document.createElement('div'); el.className = 'ms-toast'; document.body.appendChild(el); }
    el.textContent = text;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.remove(); }, 3000);
  }

  window.__moOpenQueue = function () { if (share && shareState().sharing) openQueue(); };
})();
