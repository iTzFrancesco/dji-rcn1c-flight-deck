from pathlib import Path

root = Path('.')
service_path = root / 'wireless/android-app/src/com/drone/rcn1cbridge/PortableTouchAccessibilityService.java'
activity_path = root / 'wireless/android-app/src/com/drone/rcn1cbridge/PortableTouchActivity.java'
gradle_path = root / 'wireless/android-app/build.gradle.kts'

service = service_path.read_text(encoding='utf-8')
activity = activity_path.read_text(encoding='utf-8')
gradle = gradle_path.read_text(encoding='utf-8')

service = service.replace(
    '    private String foregroundPackage = "";\n',
    '    private String foregroundPackage = "";\n    private String armedPackage = "";\n'
)

old_arm = '''    public static boolean armForGame(String label) {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> {
            s.explicitlyArmed = true;
            s.releaseRequested = false;
            lastState = "ARMED · " + label;
            s.scheduleDrive(0);
        });
        return true;
    }
'''
new_arm = '''    public static boolean armForGame(String packageName, String label) {
        PortableTouchAccessibilityService s = instance;
        if (s == null) return false;
        s.handler.post(() -> {
            if (!isSupportedGame(packageName)) {
                s.explicitlyArmed = false;
                s.armedPackage = "";
                s.releaseRequested = true;
                lastState = "Package gioco non supportato";
                s.scheduleDrive(0);
                return;
            }
            s.armedPackage = packageName;
            s.explicitlyArmed = true;
            s.releaseRequested = false;
            lastState = "ARMED · " + label;
            s.scheduleDrive(0);
        });
        return true;
    }
'''
if old_arm not in service:
    raise SystemExit('armForGame block not found')
service = service.replace(old_arm, new_arm)

service = service.replace(
    '            s.explicitlyArmed = false;\n            s.releaseRequested = true;\n            lastState = "Touch disarmato";',
    '            s.explicitlyArmed = false;\n            s.armedPackage = "";\n            s.releaseRequested = true;\n            lastState = "Touch disarmato";'
)

old_event = '''        // Never arm from Accessibility events. Unity/ColorOS may deliver stale window
        // events after Flight Bridge is foreground again, which used to leave two
        // synthetic fingers pressed over our own UI. Only an explicit game launch arms.
        if (getPackageName().equals(foregroundPackage)) {
            explicitlyArmed = false;
            releaseRequested = true;
            lastState = "Touch sospeso · Flight Bridge in primo piano";
            scheduleDrive(0);
        } else if (isSupportedGame(foregroundPackage) && !explicitlyArmed) {
            lastState = "Gioco rilevato · in attesa di avvio";
        }
'''
new_event = '''        // dispatchGesture is global Android input. Never let an armed gesture survive
        // a foreground-package change: otherwise Recents/Home/SystemUI can receive the
        // same synthetic fingers that were meant for the simulator.
        if (explicitlyArmed && (!isSupportedGame(foregroundPackage)
                || !foregroundPackage.equals(armedPackage))) {
            explicitlyArmed = false;
            armedPackage = "";
            releaseRequested = true;
            lastState = "Touch sospeso · fuori dal gioco";
            scheduleDrive(0);
            return;
        }
        if (getPackageName().equals(foregroundPackage)) {
            explicitlyArmed = false;
            armedPackage = "";
            releaseRequested = true;
            lastState = "Touch sospeso · Flight Bridge in primo piano";
            scheduleDrive(0);
        } else if (isSupportedGame(foregroundPackage) && !explicitlyArmed) {
            lastState = "Gioco rilevato · in attesa di avvio";
        }
'''
if old_event not in service:
    raise SystemExit('accessibility event block not found')
service = service.replace(old_event, new_event)

old_should = '''        return !calibrating
                && explicitlyArmed
                && !releaseRequested
                && PortableTouchBridgeService.active
                && PortableTouchBridgeService.latestFrame != null;
'''
new_should = '''        return !calibrating
                && explicitlyArmed
                && !releaseRequested
                && isSupportedGame(foregroundPackage)
                && foregroundPackage.equals(armedPackage)
                && PortableTouchBridgeService.active
                && PortableTouchBridgeService.latestFrame != null;
'''
if old_should not in service:
    raise SystemExit('shouldDrive block not found')
service = service.replace(old_should, new_should)

old_targets = '''        float radius = p.getFloat("radius", 0.17f) * dm.heightPixels;
        float lx = axis(f.lx), ly = axis(f.ly), rx = axis(f.rx), ry = axis(f.ry);
        return new float[]{
                clamp(lcX + lx * radius, 1, dm.widthPixels - 2),
                clamp(lcY - ly * radius, 1, dm.heightPixels - 2),
                clamp(rcX + rx * radius, 1, dm.widthPixels - 2),
                clamp(rcY - ry * radius, 1, dm.heightPixels - 2)
        };
'''
new_targets = '''        float radius = p.getFloat("radius", 0.17f) * dm.heightPixels;
        float density = getResources().getDisplayMetrics().density;
        float safeX = Math.max(24f * density, dm.widthPixels * 0.03f);
        float safeTop = Math.max(20f * density, dm.heightPixels * 0.04f);
        float safeBottom = Math.max(48f * density, dm.heightPixels * 0.08f);
        float lx = axis(f.lx), ly = axis(f.ly), rx = axis(f.rx), ry = axis(f.ry);
        return new float[]{
                clamp(lcX + lx * radius, safeX, dm.widthPixels - safeX),
                clamp(lcY - ly * radius, safeTop, dm.heightPixels - safeBottom),
                clamp(rcX + rx * radius, safeX, dm.widthPixels - safeX),
                clamp(rcY - ry * radius, safeTop, dm.heightPixels - safeBottom)
        };
'''
if old_targets not in service:
    raise SystemExit('targetPoints block not found')
service = service.replace(old_targets, new_targets)

old_hide = '''        if (save) {
            explicitlyArmed = true;
            releaseRequested = false;
            lastState = "ARMED · profilo calibrato";
        } else {
            explicitlyArmed = false;
            releaseRequested = true;
            lastState = "Calibrazione annullata";
        }
'''
new_hide = '''        if (save && isSupportedGame(foregroundPackage)) {
            armedPackage = foregroundPackage;
            explicitlyArmed = true;
            releaseRequested = false;
            lastState = "ARMED · profilo calibrato";
        } else {
            armedPackage = "";
            explicitlyArmed = false;
            releaseRequested = true;
            lastState = save ? "Calibrazione salvata · riapri il gioco" : "Calibrazione annullata";
        }
'''
if old_hide not in service:
    raise SystemExit('hideCalibrationOverlay block not found')
service = service.replace(old_hide, new_hide)

old_game_open = '''        Intent game = getPackageManager().getLaunchIntentForPackage("com.Freeride.Freerider_FREE");
        if (game == null) game = getPackageManager().getLaunchIntentForPackage("com.Freeride.Freerider");
        if (game == null) {
            toast("FPV Freerider non installato");
            return;
        }
'''
new_game_open = '''        String gamePackage = "com.Freeride.Freerider_FREE";
        Intent game = getPackageManager().getLaunchIntentForPackage(gamePackage);
        if (game == null) {
            gamePackage = "com.Freeride.Freerider";
            game = getPackageManager().getLaunchIntentForPackage(gamePackage);
        }
        if (game == null) {
            toast("FPV Freerider non installato");
            return;
        }
        final String targetGamePackage = gamePackage;
'''
if old_game_open not in activity:
    raise SystemExit('game launch block not found')
activity = activity.replace(old_game_open, new_game_open)

activity = activity.replace(
    'PortableTouchAccessibilityService.armForGame("FPV Freerider")',
    'PortableTouchAccessibilityService.armForGame(targetGamePackage, "FPV Freerider")'
)

if 'armForGame("FPV Freerider")' in activity:
    raise SystemExit('old armForGame call remains')

gradle = gradle.replace('versionCode = 16', 'versionCode = 17')
gradle = gradle.replace('versionName = "3.3.0-beta4"', 'versionName = "3.3.0-beta5"')

service_path.write_text(service, encoding='utf-8')
activity_path.write_text(activity, encoding='utf-8')
gradle_path.write_text(gradle, encoding='utf-8')

print('beta5 touch guard patch applied')
