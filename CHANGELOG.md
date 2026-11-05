# Changelog

## 1.1.0

### Added
- New modules: TapeMouse, AntiAFK, Cosmetics, NoCommand, AutoArmor, ClickAction, Beautification, GlassBlock, PVPVisual, PvpHitParticles, ShaderEsp, ShaderSky, TargetEspModes, Trails.
- Modern HUD (`hudmodern`) with its own renderer, font, localization, staff and notices.
- Prism ESP renderer and additional ESP visual modes.
- Cosmetics screen and avatar/item model mixins; include asset packs (textures, sounds, shaders, presets).
- Structures drag element for the HUD/drag system.
- Media utilities and world batch / mesh include helpers for rendering.
- Arrow HUD textures (chevron, double, pointer, triangle).
- Development run configuration: JVM tuning (`-Xms1G -Xmx4G`, G1GC) and `run.bat` launcher.

### Changed
- Binds, Arrows, ESP, HUD, HitSound, NoSlow, TargetESP, Notifications and ServerHelper reworked.
- Color settings extended (ColorSetting, ColorOption) and ClickGUI themes page updated.
- Main menu screen updated.
- Dependency bumps: Sodium 0.8.14, Iris 1.10.8, Fabric Loader 0.20.1.
- Dev client now includes a performance stack (Lithium, ImmediatelyFast, Entity Culling, More Culling, FerriteCore, BadOptimizations, Sodium Extra, Reese's Sodium Options, Cloth Config).

### Fixed
- Item cooldown accessors and mouse handling mixins adjusted.
- Render sampling and HUD render layer corrected.
