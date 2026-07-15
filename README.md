<p align="center">
  <img src="images/banner.png" alt="Flashlight" width="100%">
</p>

<p align="center"><em>A real flashlight for Minecraft. Smooth beam, honest shadows, blinding glare.</em></p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1.x-2b2740?style=flat-square" alt="Minecraft 26.1.x">
  <img src="https://img.shields.io/badge/Loader-Fabric-2b2740?style=flat-square" alt="Fabric">
  <img src="https://img.shields.io/badge/Requires-Fabric%20API-2b2740?style=flat-square" alt="Requires Fabric API">
  <img src="https://img.shields.io/badge/Java-25-2b2740?style=flat-square" alt="Java 25">
  <img src="https://img.shields.io/badge/Side-Client%20%2B%20Server-2b2740?style=flat-square" alt="Client + Server">
</p>

Every flashlight mod does the same trick: invisible light blocks chasing your crosshair, chunky relights, light leaking through walls. **Flashlight** doesn't. It ships its own shader-driven light engine — a smooth volumetric cone that updates every frame, stops at walls, casts soft shadows and blinds anyone who looks into the lens. Built as the companion to **[Enhanced Darkness](https://github.com/EntonioDMI/enhanced-darkness)**: in its pitch black you'll need one.

<img src="images/divider-beam.png" alt="" width="100%">

## ✦ Two lights, two jobs

|  | 🔦 Flashlight | 💡 Work Lantern |
| --- | --- | --- |
| Role | cheap all-rounder | expensive floodlight |
| Beam | **scroll to focus the lens**: wide 25-block flood ↔ razor-thin **110-block** spot | fixed ~40° wall of light, 55 blocks |
| Power | AA battery — 30 min | battery pack — 45 min |
| Recipe | iron + redstone + glass | gold, iron, redstone block |
| Loot | villages (+ AA batteries) | dungeons, mineshafts, strongholds (+ battery packs) |

- **Right-click** — on/off. **R** — slam a fresh battery in, gun-reload style, with a chest-pull animation.
- **Mouse wheel** (flashlight on, either hand) — zoom the lens: floodlight for corridors, sniper beam for scouting.
- Batteries are consumables. Below 20% charge the beam visibly dies — reload or walk in the dark.

<img src="images/divider-beam.png" alt="" width="100%">

## ✦ Night ops

- **🥽 Night Vision Goggles** — worn on the head, toggled with **G**. A real image-intensifier tube: ~3 s warm-up synced to the power-on sound, film grain, scanlines, and *honest* overexposure — daylight, torches or a flashlight beam burn out to pale green per-pixel. Runs 30 min on an AA battery, hot-swapped with the same key. Craft-only — no loot.
- **🧨 Signal Flare** — hold right-click to wind up, release to throw in an arc (~35 blocks). Lands, burns for 60 s with a flickering red point light from the same engine and a column of red smoke. Single-use, can't be extinguished.
- **🔊 Sounds** — flashlight click, lens zoom, NVG on/off whine, the low hum of a running work lantern. Subtitled (en/ru).

<img src="images/divider-batteries.png" alt="" width="100%">

## ✦ The engine

No light blocks. No chunk relights. The cone is computed **inside the game's core shaders** — terrain, entities, dropped items, particles and even vanilla mob shadow blobs are cloned on the fly with the beam math injected. That means:

- **Instant** — the light moves the frame you move, with a subtle hand-lag inertia like a real torch.
- **Honest** — light is `albedo × cone`: real textures, real colours, zero noise, works in absolute darkness.
- **Blocked by walls** — a 48³ voxel map around the camera is ray-marched (DDA) per pixel, rescanning only blocks that actually changed. Soft penumbra from a five-ray lens disk (dropping to one ray past ~25 blocks, where the penumbra is subpixel anyway), entities cast sharp capsule-silhouette shadows with PCSS edges, and the beam *dissolves* vanilla shadow circles it hits.
- **Multiplayer-native** — up to 32 sources at once, capped at 4 per chunk so a crowd in one spot never starves light elsewhere; everyone sees everyone's light, zoom level included.
- **Blinding** — glare is physics, not a sprite: its strength equals the actual cone intensity at *your* eyes, traced past blocks and mobs. Look into a lens up close and the screen whites out.
- **Sodium-ready** — with Sodium installed the beam is injected straight into its terrain shaders. Same light, same shadows, Sodium speed.

<img src="images/divider-beam.png" alt="" width="100%">

## ✦ Installation

1. Install **[Fabric Loader](https://fabricmc.net/use/)** for Minecraft **26.1.x**.
2. Install **[Fabric API](https://modrinth.com/mod/fabric-api)**.
3. Run **Java 25**.
4. Drop the jar into `mods` — on the client *and* the server (items and batteries are real).
5. Pairs best with **[Enhanced Darkness](https://github.com/EntonioDMI/enhanced-darkness)**.

> ⚠️ Iris shaderpacks replace the game's shaders entirely — with a pack enabled the beam won't light terrain. Sodium alone works out of the box.

<img src="images/divider-batteries.png" alt="" width="100%">

## ✦ Building from source

```bash
./gradlew build      # jar lands in build/libs
./gradlew runClient  # dev client
```

## ✦ License

MIT
