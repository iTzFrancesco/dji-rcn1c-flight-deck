(function () {
    'use strict';

    var context = null;
    var master = null;
    var motorBus = null;
    var motorSource = null;
    var fallbackMotors = [];
    var fallbackStarted = false;
    var loopsStarted = false;
    var enabled = true;
    var loadingPromise = null;
    var buffers = { motor: null };
    var lastThrottle = 0;
    var lastSpeed = 0;

    var ASSETS = {
        motor: 'audio/fan_interval.wav'
    };

    function createAudio() {
        if (context) return true;
        var AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) return false;
        try {
            context = new AudioContext();
            master = context.createGain();
            master.gain.value = 0;

            var compressor = context.createDynamicsCompressor();
            compressor.threshold.value = -18;
            compressor.knee.value = 12;
            compressor.ratio.value = 4;
            compressor.attack.value = 0.004;
            compressor.release.value = 0.18;
            master.connect(compressor);
            compressor.connect(context.destination);

            motorBus = context.createGain();
            motorBus.connect(master);
            return true;
        } catch (error) {
            context = null;
            master = null;
            motorBus = null;
            return false;
        }
    }

    function resume() {
        if (context && context.state === 'suspended') {
            var promise = context.resume();
            if (promise && typeof promise.catch === 'function') promise.catch(function () {});
        }
    }

    function setParam(param, value, timeConstant) {
        if (!param || !context) return;
        var now = context.currentTime;
        var smoothing = timeConstant || 0.04;
        if (typeof param.cancelScheduledValues === 'function' &&
            typeof param.setTargetAtTime === 'function') {
            param.cancelScheduledValues(now);
            param.setTargetAtTime(value, now, smoothing);
        } else if ('value' in param) {
            param.value = value;
        }
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, Number(value) || 0));
    }

    function startFallbackMotors() {
        if (fallbackStarted || !context || !motorBus) return;
        [-9, -3, 3, 9].forEach(function (detune) {
            var oscillator = context.createOscillator();
            var gain = context.createGain();
            oscillator.type = 'sawtooth';
            oscillator.detune.value = detune;
            gain.gain.value = 0;
            oscillator.connect(gain);
            gain.connect(motorBus);
            oscillator.start();
            fallbackMotors.push({ oscillator: oscillator, gain: gain });
        });
        fallbackStarted = true;
    }

    function startSampleLoop(buffer, bus) {
        if (!context || !buffer || !bus) return null;
        var source = context.createBufferSource();
        source.buffer = buffer;
        source.loop = true;
        source.connect(bus);
        source.start(0);
        return source;
    }

    function applyLevels() {
        if (!context || !master) return;
        var throttle = clamp(lastThrottle, 0, 1);
        var speed = clamp(lastSpeed, 0, 55);
        var active = enabled ? 1 : 0;
        // Liftoff-like: lower overall volume, no bubbly low-rate loop, smoother falloff for props idle
        var motorLevel = active * (0.11 + throttle * 0.26);
        var motorRate = 1.08 + throttle * 1.18 + speed * 0.007;
        setParam(master.gain, active ? 0.30 : 0, 0.07);
        setParam(motorBus.gain, motorLevel, 0.05);
        if (motorSource) setParam(motorSource.playbackRate, motorRate, 0.05);
        fallbackMotors.forEach(function (motor, index) {
            setParam(motor.oscillator.frequency, 138 + throttle * 220 + speed * 1.6 + index * 1.2, 0.04);
            setParam(motor.gain, active ? 0.11 : 0, 0.04);
        });
    }

    function startLoadedLoops() {
        if (loopsStarted || !enabled || !context) return;
        if (!buffers.motor) {
            startFallbackMotors();
            applyLevels();
            return;
        }
        motorSource = startSampleLoop(buffers.motor, motorBus);
        loopsStarted = Boolean(motorSource);
        if (!loopsStarted) startFallbackMotors();
        applyLevels();
    }

    function decodeAsset(url) {
        return fetch(url, { cache: 'force-cache' }).then(function (response) {
            if (!response.ok) throw new Error('Audio asset HTTP ' + response.status);
            return response.arrayBuffer();
        }).then(function (data) {
            return context.decodeAudioData(data);
        });
    }

    function loadAssets() {
        if (loadingPromise || !context) return;
        loadingPromise = Promise.all([
            decodeAsset(ASSETS.motor)
        ]).then(function (decoded) {
            buffers.motor = decoded[0];
            startLoadedLoops();
        }).catch(function () {
            startFallbackMotors();
            applyLevels();
        });
    }

    function prime() {
        if (!createAudio()) return false;
        resume();
        if (enabled) loadAssets();
        applyLevels();
        return true;
    }

    window.FPVAudio = {
        unlock: function () {
            return prime();
        },
        toggle: function () {
            if (!createAudio()) return false;
            resume();
            enabled = !enabled;
            if (enabled) loadAssets();
            applyLevels();
            return enabled;
        },
        update: function (throttle, speed) {
            lastThrottle = clamp(throttle, 0, 1);
            lastSpeed = clamp(speed, 0, 55);
            if (context) applyLevels();
        },
        isEnabled: function () { return enabled; },
        isReady: function () { return loopsStarted || fallbackStarted; }
    };
})();
