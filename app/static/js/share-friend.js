/*
 * Friend side of Sharing: the page someone opens by scanning the QR code on
 * the owner's printer (/p/<code>).
 *
 * Mo prints through window.MoPrinter when it exists (see js/modules/bridge.js).
 * Here that object is a stand-in: instead of writing to Bluetooth it puts the
 * print into the owner's queue. The owner's phone picks it up and prints it.
 *
 * Also adds what the Mo Mobile app adds to this page (the title-bar button and
 * the page dots), with a printer button in place of the settings gear: it
 * opens the print queue.
 */
(function () {
  var SHARE = window.MO_SHARE || {};
  var CODE = SHARE.code;
  if (!CODE) return;
  var API = '/api/share/' + encodeURIComponent(CODE);
  var NAME_KEY = 'mo_share_name';
  var state = { owner: SHARE.owner || '', sharing: true, sharingOn: true, online: true, queue: [] };
  var lastPath = '';
  var deckUntil = 0;
  // How the printer button shows the connection (gallery 09 options; one ships).
  var LOOK = 'B';

  function ownerName() { return state.owner || 'the owner'; }
  function myName() { try { return localStorage.getItem(NAME_KEY) || ''; } catch (e) { return ''; } }

  // ------------------------------------------------------------ the printer

  // Remember which print Mo just asked for; printBytes only gets the bytes.
  var realFetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    var path = url.replace(location.origin, '');
    if (path.indexOf('/api/print-data/decklist') === 0) deckUntil = Date.now() + 10 * 60 * 1000;
    else if (path.indexOf('/api/print-data/') === 0) lastPath = path;
    return realFetch(input, init);
  };

  window.MoPrinter = {
    printBytes: function () {
      if (!myName()) { askName(); return 'Type your name first, then press Print again'; }
      if (!state.sharing) { openConnecting(); return 'Connecting to ' + ownerName() + '’s printer… Press Print again once it’s connected.'; }
      var xhr = new XMLHttpRequest();
      xhr.open('POST', API + '/jobs', false);  // Mo waits for the answer
      xhr.setRequestHeader('Content-Type', 'application/json');
      try {
        xhr.send(JSON.stringify({ path: lastPath, name: myName(), deck: Date.now() < deckUntil }));
      } catch (e) {
        return 'No connection. Try again.';
      }
      var body = {};
      try { body = JSON.parse(xhr.responseText); } catch (e) { /* not json */ }
      if (xhr.status === 200) {
        state.queue = body.queue || state.queue;
        updateBadges();
        toast(body.place > 1 ? 'Added. You’re ' + ordinal(body.place) + ' in line.' : 'Added. Printing next.');
        return 'ok';
      }
      if (body.off) { refresh(); openConnecting(); return 'Connecting to ' + ownerName() + '’s printer…'; }
      return body.error || 'Couldn’t add it to the queue.';
    },
    status: function () { return JSON.stringify({ connected: true, deviceName: ownerName() + '’s printer' }); },
    choosePrinter: function () {},
  };

  function ordinal(n) {
    var s = ['th', 'st', 'nd', 'rd'], v = n % 100;
    return n + (s[(v - 20) % 10] || s[v] || s[0]);
  }

  // ----------------------------------------------------------------- style

  var css = document.createElement('style');
  css.textContent = [
    // the fixed "Mo Mobile" header and roll-up windows (same as the app)
    'body{padding-top:var(--hdr,52px)!important}',
    '.mh{position:fixed;left:0;right:0;top:0;z-index:50;display:flex;align-items:center;justify-content:space-between;font-family:Chicago7,sans-serif;color:#000}',
    '.mh .t{font-size:19px;font-weight:bold}',
    '.mh .ib{width:40px;height:40px;display:flex;align-items:center;justify-content:center;position:relative;background:none;border:0;padding:0;color:#000}',
    '.mh.B{height:44px;padding:0 10px;background:repeating-linear-gradient(to bottom,#6b6b6b 0,#6b6b6b 1px,#f8f8f8 2px,#f8f8f8 3px);border-bottom:2px solid #000}',
    '.mh.B .t{position:absolute;left:50%;transform:translateX(-50%);background:#f8f8f8;padding:0 12px}',
    '.mh.B .ib{background:#f8f8f8;width:36px;height:30px}',
    '.mh .ib .mm-badge{top:0;right:-4px}',
    '.mh .ib.mm-empty{visibility:hidden}',
    '.window.mm-shaded > :not(.title-bar){display:none!important}',
    '.window.mm-shaded{padding-bottom:0!important}',
    '.window > .title-bar{cursor:pointer;position:relative}',
    '.window > .title-bar .mm-chev{position:absolute;right:1.4rem;top:50%;transform:translateY(-50%);z-index:2;width:24px;height:22px;padding:0 3px;background:var(--gray1)!important;display:flex;align-items:center;justify-content:center}',
    '.window > .title-bar .mm-chev svg{width:18px;height:18px;transition:transform .15s}',
    '.window.mm-shaded > .title-bar .mm-chev svg{transform:rotate(180deg)}',
    '.window{border-color:#000!important;--black:#000!important}',
    '.window > .title-bar{background:repeating-linear-gradient(to bottom,var(--gray4) 0,var(--gray4) 1px,var(--gray1) 2px,var(--gray1) 3px)!important;color:#000!important;border-color:var(--gray4)!important}',
    '.window > .title-bar > span{background:var(--gray1)!important;color:#000!important}',
    '.title-bar .mm-bar-button{background:var(--gray2)}',
    '.window.is-active-window .title-bar .mm-bar-button,.window:focus-within .title-bar .mm-bar-button{background:var(--gray1)}',
    // (Mo styles every span in a title bar; these are spans too, so say it all.)
    '.title-bar .mm-bar-button .mm-badge,.title-bar .mm-bar-button .mm-state{z-index:3;margin:0}',
    '.title-bar .mm-bar-button .mm-badge,.mm-badge{position:absolute;top:-1px;right:-9px;min-width:15px;height:15px;padding:0 4px;box-sizing:border-box;border-radius:8px;background:#000;color:#fff;font-size:11px;line-height:15px;text-align:center;font-family:Chicago7,sans-serif}',
    // connection state on the printer button
    '.title-bar .mm-bar-button .mm-state,.mm-state{position:absolute;right:-3px;bottom:-1px;width:9px;height:9px;padding:0;box-sizing:border-box;border-radius:50%;border:2px solid #000;background:#fff!important}',
    '.mm-connecting .mm-state{animation:ms-blink 1.2s infinite}',
    '.title-bar .mm-connected .mm-state,.mm-connected .mm-state{background:#000!important}',
    '.mm-look-C.mm-connecting svg{opacity:.35;animation:ms-blink 1.2s infinite}',
    // modals: same as Mo's setup page (dimmed backdrop, centered window)
    '.ms-modal{position:fixed;inset:0;z-index:1000;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center}',
    '.ms-modal .window{width:calc(100% - 56px);max-width:360px;margin:0;max-height:calc(100% - 80px);display:flex;flex-direction:column}',
    '.ms-body{padding:12px 16px 16px;background:#fff;overflow:auto}',
    '.ms-lbl{font-size:11px;letter-spacing:.5px;margin:10px 0 6px}',
    '.ms-q{list-style:none;padding:0;margin:0}',
    '.ms-q li{display:flex;align-items:center;gap:12px;padding:9px 0;border-bottom:1px dotted #6b6b6b}',
    '.ms-q li:last-child{border-bottom:0}',
    '.ms-thumb{width:46px;height:64px;border:1px solid #111;background:#fff;flex:none;object-fit:cover}',
    '.ms-grow{flex:1;min-width:0}.ms-what{font-size:15px}.ms-who{font-size:12px;color:#444;margin-top:2px}',
    '.ms-you{background:#111;color:#fff;font-size:10px;padding:1px 5px;border-radius:3px;margin-left:6px}',
    '.ms-bar{height:10px;border:1px solid #111;background:#fff;margin-top:6px;overflow:hidden}',
    '.ms-bar i{display:block;height:100%;width:200%;background:repeating-linear-gradient(-45deg,#111 0 3px,#fff 3px 6px);animation:ms-run 1.2s linear infinite}',
    '@keyframes ms-run{from{transform:translateX(-12px)}to{transform:translateX(0)}}',
    '.ms-x{width:36px;height:36px;border:2px solid #111;border-radius:.4rem;background:#fff;box-shadow:inset -.1rem -.1rem 0 #c8c8c8;display:flex;align-items:center;justify-content:center;padding:0;color:#111}',
    '.ms-x svg{width:18px;height:18px}',
    '.ms-empty{font-size:14px;color:#444;padding:8px 0}',
    '.ms-field{width:100%;box-sizing:border-box;background:#fff;border:2px solid #111;height:44px;padding:0 10px;font:inherit;font-size:16px;margin:10px 0 12px}',
    '.ms-center{text-align:center}.ms-big{font-size:17px;margin:12px 0}.ms-note{font-size:13px;color:#444}',
    '.ms-toast{position:fixed;left:16px;right:16px;bottom:22px;z-index:1100;background:#fff;border:2px solid #000;box-shadow:3px 3px 0 #000;padding:10px 12px;font-size:14px;text-align:center}',
    '.ms-dots span{display:inline-block;width:8px;height:8px;margin:0 4px;background:#000;border-radius:50%;animation:ms-blink 1.2s infinite}',
    '.ms-dots span:nth-child(2){animation-delay:.2s}.ms-dots span:nth-child(3){animation-delay:.4s}',
    '@keyframes ms-blink{0%,100%{opacity:.2}50%{opacity:1}}',
  ].join('');
  document.head.appendChild(css);

  var PRINTER = '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/><rect x="6" y="14" width="12" height="8" rx="1"/></svg>';
  var PRINTER_X = '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12.531 22H7a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h6.377"/><path d="m16.5 16.5 5 5"/><path d="m16.5 21.5 5-5"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v1.5"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/></svg>';
  var PRINTER_CHECK = '<svg viewBox="0 0 24 24" width="100%" height="100%" fill="none" stroke="currentColor" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M13.5 22H7a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v.5"/><path d="m16 19 2 2 4-4"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v2"/><path d="M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6"/></svg>';
  var BACK = '<svg viewBox="0 0 24 24" width="100%" height="100%" aria-hidden="true"><path fill="currentColor" d="M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"/></svg>';
  var X = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>';

  // Same button as Mo Mobile's settings gear: a square box in the title bar.
  function barButton(bar, side, svg, label, onTap) {
    if (getComputedStyle(bar).position === 'static') bar.style.position = 'relative';
    bar.style.overflow = 'visible';
    var title = bar.querySelector('span');
    var bg = title ? getComputedStyle(title).backgroundColor : '#fff';
    var h = title ? title.getBoundingClientRect().height : bar.clientHeight;
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'mm-bar-button';
    b.setAttribute('aria-label', label);
    b.innerHTML = svg;
    b.style.cssText = 'position:absolute;' + side + ':1.4rem;top:50%;transform:translateY(-50%);z-index:2;' +
      'width:' + h + 'px;height:' + h + 'px;padding:' + Math.round(h * 0.16) + 'px;margin:0;box-sizing:border-box;' +
      'display:flex;align-items:center;justify-content:center;cursor:pointer;overflow:visible;' +
      'color:#000;border:0;border-radius:0;' +
      (bar.classList.contains('title-bar') ? '' : 'background:' + bg + ';');
    // A fixed icon size: a percentage collapses to nothing once the badge is added.
    var icon = b.querySelector('svg');
    var size = Math.max(1, Math.round(h - 2 * Math.round(h * 0.16)));
    icon.setAttribute('width', size);
    icon.setAttribute('height', size);
    icon.style.flex = 'none';
    b.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); onTap(); });
    bar.appendChild(b);
    return b;
  }

  // ---------------------------------------------------------- page chrome

  var queueButtons = [];

  var CHEV = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m18 15-6-6-6 6"/></svg>';

  function addChrome() {
    // The fixed header: printer (the queue) on the left. Friends have no settings.
    var h = document.createElement('div');
    h.className = 'mh B';
    h.innerHTML = '<button type="button" class="ib" aria-label="Print queue">' + PRINTER + '</button>' +
      '<div class="t">Mo Mobile</div><span class="ib mm-empty"></span>';
    document.body.appendChild(h);
    var b = h.querySelector('.ib');
    var icon = b.querySelector('svg');
    icon.setAttribute('width', 22); icon.setAttribute('height', 22); icon.style.flex = 'none';
    b.addEventListener('click', function () { if (state.sharing) openQueue(); else openConnecting(); });
    b.classList.add('mm-look-' + LOOK);
    queueButtons.push(b);

    // Tap a window's title to roll it up to its title bar; tap again to open it.
    [].slice.call(document.querySelectorAll('.window > .title-bar')).forEach(function (t) {
      var c = document.createElement('span');
      c.className = 'mm-chev';
      c.innerHTML = CHEV;
      t.appendChild(c);
      t.addEventListener('click', function () { t.parentElement.classList.toggle('mm-shaded'); });
    });
    addDots();
    updateBadges();
  }

  // The page dots from Mo Mobile, one set per swipeable row of windows.
  function addDots() {
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
      if (window.ResizeObserver) new ResizeObserver(place).observe(row);
    });
  }

  var shownConnected = 0;   // look B: when the check mark was first shown

  function setIcon(b, svg, key) {
    var old = b.querySelector('svg');
    if ((old.getAttribute('data-look') || 'printer') === key) return;
    var size = old.getAttribute('width') || 22;
    old.insertAdjacentHTML('afterend', svg);
    old.remove();
    var icon = b.querySelector('svg');
    icon.setAttribute('width', size);
    icon.setAttribute('height', size);
    icon.style.flex = 'none';
    icon.setAttribute('data-look', key);
  }

  function updateBadges() {
    var n = state.queue.length;
    var on = !!state.sharing;
    if (on && !shownConnected) shownConnected = Date.now();
    if (!on) shownConnected = 0;
    queueButtons.forEach(function (b) {
      b.classList.toggle('mm-connected', on);
      b.classList.toggle('mm-connecting', !on);
      b.setAttribute('aria-label', on ? 'Print queue' : 'Connecting to ' + ownerName() + '’s printer');
      if (LOOK === 'A') {
        var dot = b.querySelector('.mm-state');
        if (!dot) { dot = document.createElement('span'); dot.className = 'mm-state'; b.appendChild(dot); }
      } else if (LOOK === 'B') {
        var fresh = on && Date.now() - shownConnected < 4000;
        if (!on) setIcon(b, PRINTER_X, 'x'); else if (fresh) setIcon(b, PRINTER_CHECK, 'check'); else setIcon(b, PRINTER, 'printer');
      }
      var badge = b.querySelector('.mm-badge');
      if (!n) { if (badge) badge.remove(); return; }
      if (!badge) { badge = document.createElement('span'); badge.className = 'mm-badge'; b.appendChild(badge); }
      badge.textContent = n > 99 ? '99' : String(n);
    });
  }

  // --------------------------------------------------------------- modals

  function modal(id, title, inner, onBack) {
    var el = document.getElementById(id);
    if (!el) {
      el = document.createElement('div');
      el.id = id;
      el.className = 'ms-modal';
      el.addEventListener('click', function (e) { if (e.target === el && onBack) onBack(); });
      document.body.appendChild(el);
    }
    el.innerHTML = '<div class="window is-active-window"><div class="title-bar"><span>' + esc(title) + '</span></div>' +
      '<div class="ms-body">' + inner + '</div></div>';
    if (onBack) barButton(el.querySelector('.title-bar'), 'left', BACK, 'Back', onBack);
    return el;
  }

  function closeModal(id) { var el = document.getElementById(id); if (el) el.remove(); }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function printerTitle() { return state.owner ? state.owner + '’s Printer' : 'Mo Printer'; }

  function askName(then) {
    var el = modal('ms-name', printerTitle(),
      '<div class="ms-big" style="margin-top:4px">Who’s printing?</div>' +
      '<div class="ms-note">Shows in the queue so everyone knows whose card is whose.</div>' +
      '<input class="ms-field" id="ms-name-input" maxlength="24" autocomplete="given-name" placeholder="Your name">' +
      '<button type="button" class="action-btn primary" id="ms-name-go">Start printing</button>');
    var input = el.querySelector('#ms-name-input');
    input.value = myName();
    function go() {
      var name = input.value.trim().replace(/\s+/g, ' ');
      if (!name) { input.focus(); return; }
      try { localStorage.setItem(NAME_KEY, name); } catch (e) { /* private mode */ }
      closeModal('ms-name');
      if (then) then();
    }
    el.querySelector('#ms-name-go').addEventListener('click', go);
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });
    setTimeout(function () { input.focus(); }, 50);
  }

  var connectingOpen = false;

  function openConnecting() {
    connectingOpen = true;
    var line = state.sharingOn && !state.online
      ? ownerName() + '’s phone is offline. It needs to be on with internet.'
      : '';
    modal('ms-connecting', printerTitle(),
      '<div class="ms-center" style="padding:6px 0 4px">' +
      '<div class="ms-big">Connecting to ' + esc(ownerName()) + '’s printer…</div>' +
      '<div class="ms-dots"><span></span><span></span><span></span></div>' +
      (line ? '<div class="ms-note" style="margin-top:12px">' + esc(line) + '</div>' : '') +
      '</div>', function () { connectingOpen = false; closeModal('ms-connecting'); });
  }

  var queueOpen = false;

  function openQueue() { queueOpen = true; renderQueue(); refresh(); }

  function renderQueue() {
    if (!queueOpen) return;
    var q = state.queue;
    var now = q.filter(function (j) { return j.state === 'printing'; });
    var next = q.filter(function (j) { return j.state !== 'printing'; });
    function item(j) {
      var thumb = j.thumb ? '<img class="ms-thumb" alt="" src="' + esc(j.thumb) + '">' : '<span class="ms-thumb"></span>';
      return '<li>' + thumb + '<div class="ms-grow"><div class="ms-what">' + esc(j.label) + '</div>' +
        '<div class="ms-who">' + esc(j.name) + (j.mine ? '<span class="ms-you">YOU</span>' : '') + '</div>' +
        (j.state === 'printing' ? '<div class="ms-bar"><i></i></div>' : '') + '</div>' +
        (j.canCancel ? '<button type="button" class="ms-x" data-cancel="' + j.id + '" aria-label="Cancel">' + X + '</button>' : '') +
        '</li>';
    }
    var html = '';
    if (!q.length) html = '<div class="ms-empty">Nothing in the queue.</div>';
    if (now.length) html += '<div class="ms-lbl">NOW PRINTING</div><ul class="ms-q">' + now.map(item).join('') + '</ul>';
    if (next.length) html += '<div class="ms-lbl">UP NEXT · ' + next.length + '</div><ul class="ms-q">' + next.map(item).join('') + '</ul>';
    var el = modal('ms-queue', 'Print Queue', html, function () { queueOpen = false; closeModal('ms-queue'); });
    [].slice.call(el.querySelectorAll('[data-cancel]')).forEach(function (b) {
      b.addEventListener('click', function () {
        b.disabled = true;
        realFetch(API + '/jobs/' + b.getAttribute('data-cancel') + '/cancel', { method: 'POST' })
          .then(function (r) { return r.json(); })
          .then(function (body) { state.queue = body.queue || state.queue; updateBadges(); renderQueue(); })
          .catch(function () { b.disabled = false; });
      });
    });
  }

  // Not connected yet: ask the owner (once) to turn Sharing on.
  var asked = false;

  function ask() {
    if (asked || !myName()) return;
    asked = true;
    realFetch(API + '/ask', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: myName() }) }).catch(function () { asked = false; });
  }

  // ---------------------------------------------------------------- state

  function refresh() {
    return realFetch(API + '/state', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (s) {
        var was = state.sharing;
        state = s;
        updateBadges();
        renderQueue();
        if (s.sharing) {
          asked = false;
          if (connectingOpen) { connectingOpen = false; closeModal('ms-connecting'); }
          if (was === false) toast('Connected to ' + ownerName() + '’s printer.');
        } else {
          if (connectingOpen) openConnecting();
          if (!s.sharingOn) ask();
        }
      })
      .catch(function () {});
  }

  var toastTimer;
  function toast(text) {
    var el = document.querySelector('.ms-toast');
    if (!el) { el = document.createElement('div'); el.className = 'ms-toast'; document.body.appendChild(el); }
    el.textContent = text;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.remove(); }, 3000);
  }

  function start() {
    state.sharing = null;  // unknown until the first answer
    addChrome();
    refresh().then(function () { if (!myName()) askName(function () { if (!state.sharing) ask(); }); });
    setInterval(function () { if (!document.hidden) refresh(); }, 3000);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
