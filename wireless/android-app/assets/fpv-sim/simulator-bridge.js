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

    function clamp(value) {
        return Math.max(-1, Math.min(1, Number(value) || 0));
    }

    function axis(value) {
        return clamp(value / 32767);
    }

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
        fakeGamepad.axes[0] = axis(state.lx);     // yaw
        fakeGamepad.axes[1] = invertedAxis(axis(state.ly)); // throttle, HID Y direction
        fakeGamepad.axes[2] = axis(state.rx);     // roll
        fakeGamepad.axes[3] = invertedAxis(axis(state.ry)); // pitch, HID Y direction
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
        state.lx = lx;
        state.ly = ly;
        state.rx = rx;
        state.ry = ry;
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

    updateGamepad();
})();
