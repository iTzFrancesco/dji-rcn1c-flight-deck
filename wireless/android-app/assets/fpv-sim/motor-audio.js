(function () {
    'use strict';

    var context = null;
    var master = null;
    var motors = [];
    var enabled = false;

    function createAudio() {
        if (context) return true;
        var AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) return false;
        try {
            context = new AudioContext();
            master = context.createGain();
            master.gain.value = 0;
            master.connect(context.destination);
            [-9, -3, 3, 9].forEach(function (detune) {
                var oscillator = context.createOscillator();
                var gain = context.createGain();
                oscillator.type = 'sawtooth';
                oscillator.detune.value = detune;
                gain.gain.value = 0;
                oscillator.connect(gain);
                gain.connect(master);
                oscillator.start();
                motors.push({ oscillator: oscillator, gain: gain });
            });
            return true;
        } catch (error) {
            context = null;
            master = null;
            motors = [];
            return false;
        }
    }

    function resume() {
        if (context && context.state === 'suspended') context.resume();
    }

    function setParam(param, value) {
        if (!param || !context) return;
        var now = context.currentTime;
        if (typeof param.cancelScheduledValues === 'function' &&
            typeof param.setTargetAtTime === 'function') {
            param.cancelScheduledValues(now);
            param.setTargetAtTime(value, now, 0.035);
        } else if ('value' in param) {
            param.value = value;
        }
    }

    window.FPVAudio = {
        unlock: function () {
            if (createAudio()) resume();
            return Boolean(context);
        },
        toggle: function () {
            if (!createAudio()) return false;
            enabled = !enabled;
            resume();
            setParam(master.gain, enabled ? 0.055 : 0);
            return enabled;
        },
        update: function (throttle, speed) {
            if (!context || !master) return;
            var t = Math.max(0, Math.min(1, Number(throttle) || 0));
            var s = Math.max(0, Math.min(55, Number(speed) || 0));
            var frequency = 138 + (t * 220) + (s * 1.4);
            motors.forEach(function (motor, index) {
                setParam(motor.oscillator.frequency,
                    frequency * (1 + index * 0.010));
                setParam(motor.gain, enabled ? 0.11 : 0);
            });
        },
        isEnabled: function () { return enabled; }
    };
})();
