import { Mo } from './core.js';

/**
 * Bridge to the Android wrapper app.
 *
 * The app loads this site in a WebView and injects a `MoPrinter` object via
 * addJavascriptInterface. When that object is present, printing goes over the
 * phone's Bluetooth SPP connection to the printer. When it isn't - a normal
 * desktop browser - printing falls back to the server's own printer, and
 * sharing falls back to the Web Share API.
 *
 * The app never learns the printer protocol: it fetches a finished byte stream
 * from /api/print-data/... and writes it to the socket.
 */

Object.assign(Mo.prototype, {
    androidBridge() {
        const bridge = window.MoPrinter;
        if (!bridge || typeof bridge.printBytes !== 'function') return null;
        return bridge;
    },

    bridgeStatus() {
        const bridge = this.androidBridge();
        if (!bridge) return null;
        try {
            return JSON.parse(bridge.status());
        } catch (error) {
            return { connected: false, deviceName: null };
        }
    },

    /**
     * Fetch a printer byte stream and hand it to the Android app.
     * Returns true if the bridge handled it, false if there is no bridge.
     */
    async printViaBridge(url) {
        const bridge = this.androidBridge();
        if (!bridge) return false;

        const response = await fetch(url);
        if (!response.ok) {
            let message = `Print data unavailable (${response.status})`;
            try {
                const body = await response.json();
                if (body && body.error) message = body.error;
            } catch (error) {
                /* not json; keep the status message */
            }
            throw new Error(message);
        }

        const buffer = await response.arrayBuffer();
        const bytes = new Uint8Array(buffer);

        // addJavascriptInterface only marshals strings, so send base64.
        let binary = '';
        const step = 0x8000;
        for (let i = 0; i < bytes.length; i += step) {
            binary += String.fromCharCode.apply(
                null, bytes.subarray(i, i + step));
        }

        const result = bridge.printBytes(btoa(binary));
        if (result && result !== 'ok') {
            // Socket errors almost always mean the printer is off or asleep.
            const offline = /socket|read failed|timeout|connect|closed|refused|broken pipe/i;
            throw new Error(offline.test(result)
                ? 'Printer not responding. Turn it on and press Print again.'
                : result);
        }
        return true;
    },

    /** Let the app pick which paired printer to use. */
    chooseBridgePrinter() {
        const bridge = this.androidBridge();
        if (bridge && typeof bridge.choosePrinter === 'function') {
            bridge.choosePrinter();
        }
    },

    /** Show the bridge state in the UI, if there is anywhere to show it. */
    refreshBridgeIndicator() {
        const el = document.getElementById('bridgeStatus');
        if (!el) return;

        const status = this.bridgeStatus();
        if (!status) {
            el.style.display = 'none';
            return;
        }

        el.style.display = 'block';
        el.textContent = status.connected
            ? `Printer: ${status.deviceName || 'connected'}`
            : 'Printer: not connected';
        el.classList.toggle('is-connected', !!status.connected);

        el.onclick = () => this.chooseBridgePrinter();
    },
});
