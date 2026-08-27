(function () {
    'use strict';

    function material(THREE, color, options) {
        return new THREE.MeshStandardMaterial(Object.assign({
            color: color,
            roughness: 0.78,
            metalness: 0.08
        }, options || {}));
    }

    function addMountain(THREE, parent, x, z, radius, height, color) {
        var mountain = new THREE.Mesh(
            new THREE.ConeGeometry(radius, height, 8),
            material(THREE, color, { roughness: 1, flatShading: true })
        );
        mountain.position.set(x, height / 2 - 7, z);
        mountain.rotation.y = (x + z) * 0.003;
        var cap = new THREE.Mesh(
            new THREE.ConeGeometry(radius * 0.32, height * 0.18, 8),
            material(THREE, 0xeef2f5, { roughness: 0.95 })
        );
        cap.position.y = height * 0.41;
        mountain.add(cap);
        parent.add(mountain);
    }

    function addWarehouse(THREE, parent, x, z, w, d, h, color) {
        var group = new THREE.Group();
        group.position.set(x, 0, z);
        var wallMat = material(THREE, color, { roughness: 0.88, metalness: 0.12 });
        var roofMat = material(THREE, 0x2f3a46, { roughness: 0.92, metalness: 0.18 });
        var base = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), wallMat);
        base.position.y = h/2;
        base.castShadow = true; base.receiveShadow = true;
        group.add(base);
        var roof = new THREE.Mesh(new THREE.BoxGeometry(w+1.2, 0.6, d+1.2), roofMat);
        roof.position.y = h + 0.3; group.add(roof);
        var door = new THREE.Mesh(new THREE.PlaneGeometry(w*0.42, h*0.62), new THREE.MeshBasicMaterial({ color: 0x0b0f14 }));
        door.position.set(0, h*0.36, d/2 + 0.06); group.add(door);
        var vent = new THREE.Mesh(new THREE.CylinderGeometry(0.7, 0.7, 1.2, 10), material(THREE, 0x4a5a6a, { roughness: 0.6, metalness: 0.5 }));
        vent.position.set(w*0.18, h+1.0, 0); group.add(vent);
        parent.add(group);
    }

    function addContainer(THREE, parent, x, z, rotation, color) {
        var group = new THREE.Group();
        group.position.set(x, 0.95, z); group.rotation.y = rotation;
        var body = new THREE.Mesh(new THREE.BoxGeometry(12, 2.6, 2.45), material(THREE, color, { roughness: 0.82, metalness: 0.22 }));
        body.castShadow = true; body.receiveShadow = true; group.add(body);
        for (var i=-5;i<=5;i++) {
            var rib = new THREE.Mesh(new THREE.BoxGeometry(0.08, 2.62, 2.47), material(THREE, 0x1a222a, { roughness: 0.9 }));
            rib.position.x = i * 1.0; group.add(rib);
        }
        var doors = new THREE.Mesh(new THREE.BoxGeometry(0.05, 2.4, 2.3), material(THREE, 0x2a3340, { roughness: 0.7, metalness: 0.4 }));
        doors.position.x = 6.02; group.add(doors);
        parent.add(group);
    }

    function addTree(THREE, parent, x, z, scale, trunkColor, leafColor) {
        var tree = new THREE.Group();
        var trunk = new THREE.Mesh(
            new THREE.CylinderGeometry(0.28 * scale, 0.42 * scale, 3.4 * scale, 6),
            material(THREE, trunkColor, { roughness: 1 })
        );
        trunk.position.y = 1.7 * scale;
        tree.add(trunk);

        var leafMaterial = material(THREE, leafColor, { roughness: 0.95 });
        [
            { y: 3.1, radius: 2.0, height: 3.4 },
            { y: 5.1, radius: 1.45, height: 2.9 },
            { y: 6.8, radius: 0.85, height: 2.2 }
        ].forEach(function (layer) {
            var crown = new THREE.Mesh(
                new THREE.ConeGeometry(layer.radius * scale, layer.height * scale, 7),
                leafMaterial
            );
            crown.position.y = layer.y * scale;
            tree.add(crown);
        });

        tree.position.set(x, 0, z);
        tree.rotation.y = (x * 0.07 + z * 0.03) % (Math.PI * 2);
        parent.add(tree);
    }

    function addNeonGate(THREE, parent, x, y, z, rotation, color, mobile) {
        var gate = new THREE.Group();
        gate.position.set(x, y, z);
        gate.rotation.y = rotation;

        var neon = material(THREE, color, {
            roughness: 0.25,
            metalness: 0.45,
            emissive: color,
            emissiveIntensity: mobile ? 0.9 : 1.35
        });
        var dark = material(THREE, 0x14202b, { roughness: 0.55, metalness: 0.55 });
        var ring = new THREE.Mesh(
            new THREE.TorusGeometry(8.5, 0.24, 6, mobile ? 18 : 28),
            neon
        );
        ring.position.y = 7.2;
        gate.add(ring);

        [-8.5, 8.5].forEach(function (xPos) {
            var post = new THREE.Mesh(
                new THREE.CylinderGeometry(0.3, 0.42, 7.2, 7),
                dark
            );
            post.position.set(xPos, 3.6, 0);
            gate.add(post);
            var lamp = new THREE.Mesh(
                new THREE.SphereGeometry(0.45, mobile ? 6 : 8, 5),
                neon
            );
            lamp.position.set(xPos, 7.15, 0);
            gate.add(lamp);
        });

        parent.add(gate);
    }

    function addLandingPad(THREE, parent) {
        var pad = new THREE.Group();
        var base = new THREE.Mesh(
            new THREE.CylinderGeometry(13, 13, 0.18, 32),
            material(THREE, 0x18232d, { roughness: 0.92 })
        );
        base.position.y = 0.08;
        pad.add(base);

        var ring = new THREE.Mesh(
            new THREE.TorusGeometry(11.4, 0.26, 6, 32),
            material(THREE, 0x75e6d0, {
                roughness: 0.35,
                metalness: 0.35,
                emissive: 0x0b6d66,
                emissiveIntensity: 1.2
            })
        );
        ring.rotation.x = Math.PI / 2;
        ring.position.y = 0.22;
        pad.add(ring);

        var stripe = material(THREE, 0xe6b85c, {
            roughness: 0.42,
            metalness: 0.18,
            emissive: 0x6b4612,
            emissiveIntensity: 0.45
        });
        [-3.3, 3.3].forEach(function (xPos) {
            var marker = new THREE.Mesh(new THREE.BoxGeometry(1.1, 0.05, 7.5), stripe);
            marker.position.set(xPos, 0.2, 0);
            marker.rotation.y = Math.PI / 4;
            pad.add(marker);
        });

        parent.add(pad);
    }

    function addTrainingWorld(scene, THREE, options) {
        var mobile = Boolean(options && options.mobile);
        var decor = new THREE.Group();
        decor.name = 'FPVTrainingDecor';

        addMountain(THREE, decor, -205, 245, 90, 100, 0x33495d);
        addMountain(THREE, decor, -70, 275, 125, 135, 0x3c5367);
        addMountain(THREE, decor, 95, 270, 105, 115, 0x344d61);
        addMountain(THREE, decor, 235, 235, 115, 125, 0x2d4356);
        addMountain(THREE, decor, -245, 90, 80, 82, 0x415c6b);
        addMountain(THREE, decor, 250, 65, 95, 90, 0x3c5868);

        addLandingPad(THREE, decor);
        // Liftoff bando freestyle additions
        addWarehouse(THREE, decor, -42, 38, 22, 16, 9, 0xc2b8a3);
        addWarehouse(THREE, decor, 58, -42, 20, 14, 8, 0xb9c0c9);
        addContainer(THREE, decor, -8, 18, 0.32, 0xd94a3a);
        addContainer(THREE, decor, -6, 20.6, 0.32, 0x2f7d62);
        addContainer(THREE, decor, 28, -18, -0.45, 0x3a6ea5);
        addContainer(THREE, decor, -34, -26, 0.78, 0xe2b84d);

        var gateSpecs = [
            { x: 0, y: 0.6, z: -48, r: 0, c: 0x70f0d5 },
            { x: 46, y: 3.2, z: 2, r: Math.PI / 2, c: 0xffc861 },
            { x: -54, y: 6.5, z: 34, r: 0.18, c: 0xff7f9e },
            { x: 70, y: 2.4, z: 74, r: -0.32, c: 0x86a8ff },
            { x: -76, y: 4.2, z: 92, r: 0.48, c: 0xd6ff6f }
        ];
        gateSpecs.forEach(function (spec) {
            addNeonGate(THREE, decor, spec.x, spec.y, spec.z, spec.r, spec.c, mobile);
        });

        var treeSpots = [
            [-150, -150, 1.4], [-116, -118, 1.0], [120, -150, 1.3], [164, -118, 1.1],
            [-150, -20, 1.2], [145, -28, 1.5], [-155, 72, 1.0], [158, 92, 1.3],
            [-120, 145, 1.4], [-42, 145, 1.0], [70, 145, 1.2], [146, 150, 1.0],
            [-180, 25, 0.9], [185, 35, 1.1], [-25, 205, 1.3], [35, 205, 1.0],
            [-210, -65, 1.1], [215, -72, 1.2]
        ];
        var maxTrees = mobile ? 10 : treeSpots.length;
        treeSpots.slice(0, maxTrees).forEach(function (spot, index) {
            addTree(THREE, decor, spot[0], spot[1], spot[2], 0x5c3f35,
                index % 2 ? 0x32675d : 0x3d805e);
        });

        scene.add(decor);
        return decor;
    }

    function addDroneDetails(droneGroup, THREE, mobile) {
        var canopyMaterial = material(THREE, 0x0e1822, { roughness: 0.18, metalness: 0.82 });
        var canopy = new THREE.Mesh(new THREE.SphereGeometry(0.27, mobile ? 10 : 14, 7), canopyMaterial);
        canopy.scale.set(1.08, 0.52, 1.42); canopy.position.set(0, 0.17, 0.11); droneGroup.add(canopy);
        var camHousing = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.15, 0.19), material(THREE, 0x293746, { roughness: 0.25, metalness: 0.72 }));
        camHousing.position.set(0, 0.20, 0.44); droneGroup.add(camHousing);
        var lens = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.07, 0.04, 12), new THREE.MeshStandardMaterial({ color: 0x0a0a0a, roughness: 0.12, metalness: 0.9 }));
        lens.rotation.x = Math.PI/2; lens.position.set(0, 0.20, 0.54); droneGroup.add(lens);
        var ledMaterial = material(THREE, 0x6fffe2, { roughness: 0.2, emissive: 0x2aa890, emissiveIntensity: 2.0 });
        var tailLedMat = material(THREE, 0xff4d5a, { roughness: 0.2, emissive: 0x9a1a22, emissiveIntensity: 1.7 });
        [-0.58, 0.58].forEach(function (xPos) {
            var led = new THREE.Mesh(new THREE.SphereGeometry(0.065, 7, 5), ledMaterial);
            led.position.set(xPos, 0.12, 0.60); droneGroup.add(led);
        });
        [-0.58, 0.58].forEach(function (xPos) {
            var led = new THREE.Mesh(new THREE.SphereGeometry(0.055, 6, 4), tailLedMat);
            led.position.set(xPos, 0.12, -0.52); droneGroup.add(led);
        });
        var ant = new THREE.Mesh(new THREE.CylinderGeometry(0.015, 0.015, 0.42, 6), material(THREE, 0x111111, { roughness: 0.9 }));
        ant.position.set(0, 0.26, -0.22); ant.rotation.x = 0.35; droneGroup.add(ant);
    }

    window.FPVAssets = {
        addTrainingWorld: addTrainingWorld,
        addDroneDetails: addDroneDetails
    };
})();
