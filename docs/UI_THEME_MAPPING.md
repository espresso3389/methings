# UI Theme Mapping

This doc records how the Web UI (files/www/index.html) CSS tokens map to Android theme colors.

Android XML comments cannot include `--` (double hyphen), so we keep the exact CSS var names here.

## Status/Navigation Bars

- Web UI topbar background: `--bg-raised` (currently `#18181b` in `index.html`)
  - Android: `Theme.Methings` `android:statusBarColor` -> `@color/methings_system_bar`
  - Android: `Theme.Methings` `android:navigationBarColor` -> `@color/methings_navigation_bar`

