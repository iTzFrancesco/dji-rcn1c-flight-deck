(function () {
    'use strict';

    var state = {
        lx: 0,
        ly: 0,
        rx: 0,
        ry: 0,
        buttonMask: 0x1000,
        mode: 1,
        packetsPerSecond: 0,
        connected: false
    };
    var gamepadAnnounced = false;
    var desktopSocket = null;
    var desktopReconnectTimer = null;

    function clampNormalized(value) {
        return Math.max(-1, Math.min(1, Number(value) || 0));
    }

    function isRawRcFrame(values) {
        return values.some(function (value) { return Math.abs(Number(value) || 0) > 4; }) &&
            values.every(function (value) {
                var number = Number(value);
                return Number.isFinite(number) && number >= 0 && number <= 2048;
            });
    }

    function normalizeRcAxis(value, rawFrame) {
        if (rawFrame) return clampNormalized(((Number(value) || 1024) - 1024) / 660);
        return clampNormalized((Number(value) || 0) / 32767);
    }

    var fakeGamepad = {
        id: 'DJI RC-N1C raw bridge',
        index: 0,
        connected: false,
        timestamp: 0,
        mapping: '',
        axes: [0, 0, 0, 0],
        buttons: [],
        vibrationActuator: null,
        hapticActuators: []
    };

    function invertedAxis(value) {
        return value === 0 ? 0 : -value;
    }

    function updateGamepad() {
        // Emulate the standard Mode 2 HID axis order expected by FPV.Sim.
        if (!state.connected) {
            fakeGamepad.axes[0] = 0;
            fakeGamepad.axes[1] = 1;
            fakeGamepad.axes[2] = 0;
            fakeGamepad.axes[3] = 0;
            fakeGamepad.timestamp = typeof performance !== 'undefined'
                ? performance.now()
                : Date.now();
            return;
        }
        fakeGamepad.axes[0] = state.lx;     // yaw
        fakeGamepad.axes[1] = invertedAxis(state.ly); // throttle, HID Y direction
        fakeGamepad.axes[2] = state.rx;     // roll
        fakeGamepad.axes[3] = invertedAxis(state.ry); // pitch, HID Y direction
        fakeGamepad.timestamp = typeof performance !== 'undefined'
            ? performance.now()
            : Date.now();
    }

    function dispatchGamepadEvent(type) {
        var event = new Event(type);
        Object.defineProperty(event, 'gamepad', { value: fakeGamepad });
        window.dispatchEvent(event);
    }

    window.setRcn1cFrame = function (lx, ly, rx, ry, buttonMask, mode, packetsPerSecond) {
        var values = [lx, ly, rx, ry];
        var rawFrame = isRawRcFrame(values);
        state.lx = normalizeRcAxis(lx, rawFrame);
        state.ly = normalizeRcAxis(ly, rawFrame);
        state.rx = normalizeRcAxis(rx, rawFrame);
        state.ry = normalizeRcAxis(ry, rawFrame);
        state.buttonMask = buttonMask;
        state.mode = mode;
        state.packetsPerSecond = packetsPerSecond;
        state.connected = true;
        fakeGamepad.connected = true;
        updateGamepad();
        if (!gamepadAnnounced) {
            gamepadAnnounced = true;
            dispatchGamepadEvent('gamepadconnected');
        }
    };

    // Canonical Mode 2 controls for the simulator. This bypasses browser-specific
    // Gamepad axis conventions and accepts both Android raw RC values and desktop int16 values.
    window.getRcn1cFlightControls = function () {
        return {
            connected: state.connected,
            yaw: state.lx,
            throttle: state.ly,
            roll: state.rx,
            pitch: state.ry
        };
    };

    window.setRcn1cStatus = function (message, connected) {
        state.connected = Boolean(connected);
        fakeGamepad.connected = state.connected;
        updateGamepad();
        if (!state.connected && gamepadAnnounced) {
            gamepadAnnounced = false;
            dispatchGamepadEvent('gamepaddisconnected');
        }
        var element = document.getElementById('rcn1c-bridge-status');
        if (element) {
            element.textContent = message || 'RC non collegato';
            element.style.color = state.connected ? '#2ED573' : '#FFB86B';
        }
        var gameState = document.getElementById('game-bridge-state');
        var gameDot = typeof document.querySelector === 'function'
            ? document.querySelector('#bridge-chip i')
            : null;
        if (gameState) gameState.textContent = state.connected ? 'ONLINE' : 'IN ATTESA';
        if (gameDot) {
            gameDot.style.background = state.connected ? '#75E6D0' : '#FFB86B';
            gameDot.style.boxShadow = state.connected
                ? '0 0 10px rgba(117,230,208,.8)'
                : '0 0 10px rgba(255,184,107,.7)';
        }
    };

    // The direct seam is reliable even on WebViews where navigator.getGamepads
    // is missing or exposed as a non-configurable native property.
    window.getRcn1cGamepads = function () {
        return [fakeGamepad];
    };

    var nativeGetGamepads = typeof navigator.getGamepads === 'function'
        ? navigator.getGamepads.bind(navigator)
        : null;
    try {
        Object.defineProperty(navigator, 'getGamepads', {
            configurable: true,
            value: function () {
                var pads = nativeGetGamepads ? Array.prototype.slice.call(nativeGetGamepads()) : [];
                pads[0] = fakeGamepad;
                return pads;
            }
        });
    } catch (error) {
        console.warn('RC-N1C: Gamepad API non sostituibile', error);
    }

    var status = document.createElement('div');
    status.id = 'rcn1c-bridge-status';
    status.textContent = 'In attesa del RC-N1C...';
    status.style.cssText = [
        'position:fixed', 'left:12px', 'bottom:10px', 'z-index:4000',
        'padding:5px 8px', 'border:1px solid #385064', 'border-radius:6px',
        'background:rgba(7,13,20,.82)', 'font:11px monospace',
        'color:#FFB86B', 'pointer-events:none'
    ].join(';');
    document.body.appendChild(status);

    function desktopHost() {
        if (!window.location || !window.location.hostname) return false;
        return window.location.hostname === '127.0.0.1' ||
            window.location.hostname === 'localhost';
    }

    function modeCode(mode) {
        if (mode === 'SPORT') return 0;
        if (mode === 'CINE') return 2;
        return 1;
    }

    function connectDesktopBridge() {
        if (!desktopHost() || typeof WebSocket !== 'function') return;
        var wsUrl = 'ws://' + window.location.hostname + ':8124';
        try {
            desktopSocket = new WebSocket(wsUrl);
            desktopSocket.onopen = function () {
                window.setRcn1cStatus('Dashboard PC · attendo RC-N1C', false);
            };
            desktopSocket.onmessage = function (event) {
                var message;
                try {
                    message = JSON.parse(event.data);
                } catch (error) {
                    return;
                }
                if (message.t !== 's') return;
                if (!message.connected) {
                    window.setRcn1cStatus('Dashboard PC · RC scollegato', false);
                    return;
                }
                window.setRcn1cFrame(
                    (Number(message.lx) || 0) * 32767,
                    (Number(message.ly) || 0) * 32767,
                    (Number(message.rx) || 0) * 32767,
                    (Number(message.ry) || 0) * 32767,
                    Number(message.button_mask) || 0x1000,
                    modeCode(message.mode),
                    Number(message.pps) || 0
                );
                window.setRcn1cStatus(
                    'PC · ' + (message.port || 'bridge attivo'),
                    true
                );
            };
            desktopSocket.onclose = function () {
                desktopSocket = null;
                window.setRcn1cStatus('Dashboard PC non raggiungibile', false);
                if (!desktopReconnectTimer) {
                    desktopReconnectTimer = window.setTimeout(function () {
                        desktopReconnectTimer = null;
                        connectDesktopBridge();
                    }, 1500);
                }
            };
            desktopSocket.onerror = function () {
                if (desktopSocket) desktopSocket.close();
            };
        } catch (error) {
            desktopSocket = null;
        }
    }

    updateGamepad();
    connectDesktopBridge();
})();
