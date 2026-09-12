# Model attribution

## quaternius-base.glb (default)

Animated Base Character by Quaternius.
Source: https://poly.pizza/m/cwYvO5UauX
License: Creative Commons Attribution 3.0 Unported
https://creativecommons.org/licenses/by/3.0/

The downloaded GLB is unmodified. Calico supplies its own runtime exercise poses and
simple material shading; the file's 45 embedded animations are not played.
Its 53-joint rig is mapped through explicit DEF-* bone aliases. Finger joints are
collapsed by the renderer's joint palette for the mobile shader budget.

Validation: all 16 exercise clips passed finite-mesh and elbow/knee angle checks;
the default model passed the GLES render smoke test on the Android 16 emulator.
The workout preview was also visually checked in the emulator. Physical-device
rendering of this replacement remains to be checked.

## human.glb

Cesium Man, from the Khronos glTF sample models.
Copyright Cesium, licensed CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).
Source: https://github.com/KhronosGroup/glTF-Sample-Models

Used unmodified as the stand-in avatar for the AR exercise preview. The rig is driven at
runtime by pose clips; the file's own walk animation is ignored.

## Replacing it with your own rig

`DemoFigure.modelAssets` tries `quaternius-base.glb`, then `human.glb`.
The first model with a compatible rig and weighted mesh wins. A rig is recognised by its bone names, listed in `HumanRig`, which already
cover Blender metarig naming (`hips`, `spine`, `chest`, `upper_arm_L`, `thigh_R`, ...),
Mixamo naming and the Khronos sample's own naming.

Drop a `.glb` in this folder and add it to that list. A `.gltf` works too, but its
external buffer has to sit next to it; `RigCoverageTest` fails if that file is missing.

The repository's `assets/rig-body.gltf` references a missing 140,160-byte `buffer.bin`.
Re-export it as `rig-body.glb`, or copy both files into this Android assets folder.
The JSON alone cannot reconstruct its vertex data.

## Online candidates (reviewed 2026-09-12)

| Candidate | License and format | Fit for this demo |
| --- | --- | --- |
| [Quaternius Universal Base Characters](https://quaternius.com/packs/universalbasecharacters.html) | CC0; glTF, Blender, FBX, OBJ | Recommended visual candidate: humanoid base characters. Inspect bone names and test retargeting before replacing the bundled model. |
| [Kenney Animated Characters Protagonists](https://kenney.nl/assets/animated-characters-protagonists) | CC0; confirm formats in the downloaded pack | Alternative stylized characters; proportions and bone mapping need review. |
| [Khronos RiggedFigure](https://github.com/KhronosGroup/glTF-Sample-Assets/tree/main/Models/RiggedFigure) | CC BY 4.0; glTF | Useful skeletal test asset, not necessarily a visual upgrade. |

These candidates have not been imported or verified with this renderer. It supports
one skin, base material/vertex colors and runtime landmark animation; it does not
render texture maps, PBR materials, morph targets or embedded animation clips. Models
that rely on those features will not look like their online previews.

The current Sample Assets [Cesium Man license](https://github.com/KhronosGroup/glTF-Sample-Assets/blob/main/Models/CesiumMan/LICENSE.md)
also identifies Cesium trademark limitations. Keep the attribution and review that
license when distributing a replacement from the current repository.
