package com.storyteller.domain.model

/**
 * Which palette the app renders in.
 *
 * [System] follows the device's own light/dark setting; the other two override
 * it. [Dark] is the default — see SettingsRepository for why an unreadable or
 * unrecognised stored value resolves there rather than to [System].
 */
enum class ThemeChoice { System, Light, Dark }
