package com.storyteller.ui.theme

import androidx.compose.ui.graphics.Color

// Light. The two colours the app already had — a warm page cream and a deep
// ink — carried over from res/values/colors.xml so the light theme keeps the
// storybook identity rather than Material's default purple-on-white.
internal val PageCream = Color(0xFFFDFBF6)
internal val Ink = Color(0xFF1B2A4A)
internal val PageHighlight = Color(0xFFE6DEC6)

// Dark. A deep navy rather than pure black: the reader is a wall of text read in
// a dim room, and #000 against near-white type is harsher to read at length than
// a slightly lifted ground.
internal val NightGround = Color(0xFF10141F)
internal val NightSurface = Color(0xFF161B29)
internal val NightType = Color(0xFFE8E4DA)
internal val NightAccent = Color(0xFFB9C7E8)
internal val NightHighlight = Color(0xFF2A3450)
