# UI visual correction implementation plan

**Goal:** Correct the visual regressions found on the installed MIUIX build and implement distinct page presentation for Material 3, MIUIX and COUI while retaining every business action and state.

**Approved direction:** User requested implementation after the UI review. MIUIX uses large titles and grouped neutral surfaces; COUI uses centered titles, compact sections and native controls; Material uses standard top bars, tonal emphasis and Material navigation.

**Constraints:** Work in the current dirty checkout; preserve existing unrelated changes. Module version comes from module.prop. No dependency upgrades, signing changes, phone installation, flash, upload or publication. No added or executed tests unless requested. Compile and package a new installable Debug APK and a ZIP with the same APK; verify metadata, hashes and manifest coverage.

- [x] Theme task: correct native palette roles, title/header treatment and tab backgrounds/selection. Own ZiyuTheme, ZiyuNativeThemeTokens, ZiyuCompactLayout, ZiyuTabs and ZiyuIconSystem.
- [x] Controls task: correct slot foreground ownership, primary/secondary action roles, native squircle borders and card shape contract, native list rows. Own ui/design and ZiyuCard only.
- [x] Settings task: improve grouped settings, preview density, native typography and choice presentation. Own SettingsHubScreen only.
- [x] Main integration: remodel the current-font area, connect style-specific page presentation without dropping new tools, diagnostics or mount/trust actions; improve library/log/studio hierarchy and app backdrop/navigation.
- [x] Review: inspect all callbacks and state owners; compare component semantics and theme surfaces; inspect changes for feature loss and conflicting file ownership.
- [x] Build/package: assembleDebug, package from this checkout into a new timestamp directory; verify APK signature/package/version, ZIP CRC, payload manifest and embedded APK SHA-256 equality.

## Interface ownership

All public component signatures and Route state/action interfaces remain compatible. Agents do not run Gradle or packaging concurrently. Main owns final integration and deliverables. Existing unused page implementations must not be activated unless they retain the modern route's full feature set.

## Acceptance

Native tab background blends with its parent and its selected state remains visible. Button foreground follows its own enabled/disabled colors. Native strokes match native contours. Long font names do not dominate the homepage. MIUIX/COUI cards have neutral surfaces with restrained accent areas. Page style dispatch is explicit, shared state and actions have a single owner. Compilation/package validation is reported separately from new-build phone visual acceptance.
