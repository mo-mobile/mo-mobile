import { Mo } from './core.js';

const DOWNLOAD_ICON = '<svg viewBox="0 0 24 24" width="100%" height="100%" aria-hidden="true"><path fill="currentColor" d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"/></svg>';

Object.assign(Mo.prototype, {
    setupDecklistListeners() {
        this.decklistCards = [];
        document.getElementById('decklistPreviewBtn').addEventListener('click', () => this.decklistPreview());
        document.getElementById('decklistBackBtn').addEventListener('click', () => this.decklistGoBack());
        document.getElementById('decklistPrintBtn').addEventListener('click', () => this.decklistPrint());
    },

    parseDecklistText(text) {
        const entries = [];
        let section = 'deck';
        for (const rawLine of text.split('\n')) {
            const line = rawLine.trim();
            if (!line) continue;
            const lower = line.toLowerCase();
            if (lower === 'deck' || lower === 'maindeck' || lower === 'main deck') { section = 'deck'; continue; }
            if (lower === 'sideboard' || lower === 'side' || lower === 'sb') { section = 'sideboard'; continue; }
            const match = line.match(/^(\d+)x?\s+(.+)$/);
            if (match) {
                entries.push({ quantity: parseInt(match[1], 10), name: match[2].trim(), section });
            }
        }
        return entries;
    },

    async decklistPreview() {
        const text = document.getElementById('decklistTextarea').value.trim();
        const errorDiv = document.getElementById('decklistInputError');
        errorDiv.style.display = 'none';
        const entries = this.parseDecklistText(text);
        if (!entries.length) {
            errorDiv.textContent = 'No cards found. Paste a list like "4 Counterspell".';
            errorDiv.style.display = 'block';
            return;
        }
        document.getElementById('decklistInputSection').style.display = 'none';
        document.getElementById('decklistLoadingState').style.display = 'flex';
        try {
            const names = entries.map(e => e.name);
            const resp = await fetch('/api/decklist/lookup', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ names }),
            });
            const data = await resp.json();
            const results = data.results || [];
            this.decklistCards = entries.map((entry, i) => ({ ...entry, ...(results[i] || {}) }));
            document.getElementById('decklistLoadingState').style.display = 'none';
            this._renderDecklistPreview(this.decklistCards);
            document.getElementById('decklistPreviewSection').style.display = 'block';
            const win = document.querySelector('.decklist-window');
            if (win) {
                const top = win.getBoundingClientRect().top + window.scrollY - 8;
                window.scrollTo({ top, behavior: 'smooth' });
            }
        } catch (e) {
            console.error('Decklist lookup failed:', e);
            document.getElementById('decklistLoadingState').style.display = 'none';
            document.getElementById('decklistInputSection').style.display = 'block';
            errorDiv.textContent = 'Lookup failed. Is the server running?';
            errorDiv.style.display = 'block';
        }
    },

    _renderDecklistPreview(cards) {
        const container = document.getElementById('decklistCards');
        container.innerHTML = '';
        const found = cards.filter(c => c.found).length;
        const status = document.getElementById('decklistStatus');
        status.textContent = `${found} of ${cards.length} cards found locally.`;
        const missing = cards.filter(c => !c.found && c.card_type);
        if (missing.length) {
            // Download icon at the right of the bar: offers to fetch every missing card.
            status.style.display = 'flex';
            status.style.alignItems = 'center';
            status.style.justifyContent = 'space-between';
            status.style.gap = '8px';
            const all = document.createElement('button');
            all.type = 'button';
            all.className = 'decklist-download-all';
            all.setAttribute('aria-label', 'Download missing cards');
            all.innerHTML = DOWNLOAD_ICON;
            all.style.cssText = 'flex:0 0 auto;width:30px;height:30px;padding:3px;border:0;background:none;color:#000;cursor:pointer;';
            all.addEventListener('click', () => this._confirmDownloadAll(missing));
            status.appendChild(all);
        }
        let lastSection = null;
        cards.forEach((card, index) => {
            if (card.section !== lastSection) {
                lastSection = card.section;
                const label = document.createElement('div');
                label.className = 'decklist-section-label';
                label.textContent = card.section === 'sideboard' ? 'Sideboard' : 'Deck';
                container.appendChild(label);
            }
            const entry = document.createElement('div');
            entry.className = 'decklist-card-entry' + (card.found ? '' : ' not-found');
            const removeBtn = document.createElement('button');
            removeBtn.className = 'decklist-card-remove';
            removeBtn.textContent = '×';
            removeBtn.addEventListener('click', () => {
                this.decklistCards.splice(index, 1);
                this._renderDecklistPreview(this.decklistCards);
            });
            entry.appendChild(removeBtn);
            if (card.found && card.imageUrl) {
                const img = document.createElement('img');
                img.src = card.imageUrl;
                img.alt = card.name;
                entry.appendChild(img);
            } else {
                const placeholder = document.createElement('div');
                placeholder.className = 'decklist-card-not-found';
                placeholder.textContent = card.name + ' (not found)';
                entry.appendChild(placeholder);
                if (card.card_type) {
                    // Tap a missing card (or its download icon) to fetch just that one.
                    const dl = document.createElement('span');
                    dl.className = 'decklist-card-download';
                    dl.innerHTML = DOWNLOAD_ICON;
                    dl.style.cssText = 'position:absolute;top:8px;left:8px;width:26px;height:26px;color:#000;' +
                        'background:#fff;border:1px solid #000;padding:2px;box-sizing:border-box;';
                    entry.style.position = entry.style.position || 'relative';
                    entry.appendChild(dl);
                    placeholder.style.cursor = 'pointer';
                    const fetchOne = async e => {
                        e.stopPropagation();
                        placeholder.textContent = 'Downloading…';
                        if (await this._fetchCard(card)) this._renderDecklistPreview(this.decklistCards);
                        else placeholder.textContent = card.name + ' (download failed, tap to retry)';
                    };
                    placeholder.addEventListener('click', fetchOne);
                    dl.addEventListener('click', fetchOne);
                }
            }
            const nameBar = document.createElement('div');
            nameBar.className = 'decklist-card-label';
            nameBar.textContent = `${card.quantity}x - ${card.name}`;
            entry.appendChild(nameBar);
            container.appendChild(entry);
        });
    },

    /** Download one missing card; updates it in place. Returns true if it worked. */
    async _fetchCard(card) {
        try {
            const resp = await fetch('/api/decklist/fetch-card', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: card.name, card_type: card.card_type, cmc: card.cmc, filename: card.filename }),
            });
            const data = await resp.json();
            if (!resp.ok || !data.found) return false;
            // The same card can be in the list twice (deck and sideboard).
            this.decklistCards.forEach(c => {
                if (c.name === card.name) { c.found = true; c.imageUrl = data.imageUrl; }
            });
            return true;
        } catch (e) {
            console.error('Card download failed:', e);
            return false;
        }
    },

    /** Pop-up drawn like Mo's windows: download every missing card. */
    _confirmDownloadAll(missing) {
        const unique = [...new Map(missing.map(c => [c.name, c])).values()];
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:1000;background:rgba(0,0,0,.35);' +
            'display:flex;align-items:center;justify-content:center;';
        overlay.innerHTML = `
            <div class="window is-active-window" style="width:calc(100% - 56px);max-width:360px;margin:0;">
                <div class="title-bar"><span>Download All</span></div>
                <div class="panel" style="padding:14px;">
                    <p class="dl-text" style="margin:0 0 14px;">Download the ${unique.length} missing card${unique.length > 1 ? 's' : ''} in this deck?</p>
                    <div style="display:flex;gap:12px;">
                        <button type="button" class="action-btn dl-cancel" style="flex:1">Cancel</button>
                        <button type="button" class="action-btn primary dl-go" style="flex:1">Download All</button>
                    </div>
                </div>
            </div>`;
        document.body.appendChild(overlay);
        const text = overlay.querySelector('.dl-text');
        const go = overlay.querySelector('.dl-go');
        const cancel = overlay.querySelector('.dl-cancel');
        let stop = false;
        cancel.addEventListener('click', () => { stop = true; overlay.remove(); this._renderDecklistPreview(this.decklistCards); });
        go.addEventListener('click', async () => {
            go.disabled = true;
            go.style.display = 'none';
            cancel.textContent = 'Stop';
            let done = 0, failed = 0;
            for (const card of unique) {
                if (stop) return;
                text.textContent = `Downloading ${done + failed + 1} of ${unique.length}: ${card.name}`;
                if (await this._fetchCard(card)) done++; else failed++;
                await new Promise(r => setTimeout(r, 120));  // go easy on Scryfall
            }
            overlay.remove();
            this._renderDecklistPreview(this.decklistCards);
            if (failed) {
                const status = document.getElementById('decklistStatus');
                status.firstChild.textContent += ` ${failed} couldn't be downloaded.`;
            }
        });
    },

    decklistGoBack() {
        document.getElementById('decklistPreviewSection').style.display = 'none';
        document.getElementById('decklistInputSection').style.display = 'block';
        document.getElementById('decklistInputError').style.display = 'none';
    },

    async decklistPrint() {
        const printable = this.decklistCards.filter(c => c.found && c.card_type);
        if (!printable.length) return;
        const btn = document.getElementById('decklistPrintBtn');
        btn.disabled = true;
        btn.textContent = 'Printing...';
        try {
            const payload = printable.map(c => ({
                card_type: c.card_type,
                cmc: c.cmc,
                filename: c.filename,
                quantity: c.quantity,
            }));
            if (this.androidBridge()) {
                // Ask the server to expand the list, then print each card as
                // its own job. One continuous stream is what stalls the printer.
                const planResponse = await fetch('/api/print-data/decklist', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cards: payload }),
                });
                const plan = await planResponse.json();
                if (!plan.success) throw new Error(plan.error || 'Decklist failed');

                for (let i = 0; i < plan.jobs.length; i++) {
                    btn.textContent = `Printing ${i + 1}/${plan.jobs.length}...`;
                    await this.printViaBridge(plan.jobs[i].url);
                }
                if (plan.missing && plan.missing.length) {
                    console.warn('No image for:', plan.missing.join(', '));
                }
            } else {
                await fetch('/api/decklist/print', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ cards: payload }),
                });
            }
        } catch (e) {
            console.error('Decklist print failed:', e);
        } finally {
            btn.disabled = false;
            btn.textContent = 'Print All';
        }
    },
});
