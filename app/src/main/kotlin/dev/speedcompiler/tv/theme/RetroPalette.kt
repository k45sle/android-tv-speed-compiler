package dev.speedcompiler.tv.theme

/** Fixed retro colors, taken from the pixel logo. the XPM files in tools/pixel may only use colors from [RetroColors]. */
object Retro {
    const val LOGO_BASE = 0xFF153C29.toInt()
    const val CREAM = 0xFFF2E6C4.toInt()
    const val GOLD = 0xFFE8A33A.toInt()

    // line, ok, warn, error per mode; every text color passes 4.5:1 on background, surface and container (RetroTest).
    val Dark = Palette(
        accent = GOLD, onAccent = 0xFF0B2017.toInt(),
        container = 0xFF1C4A33.toInt(), onContainer = CREAM,
        background = 0xFF0B2017.toInt(), surface = 0xFF0B2017.toInt(), surfaceVariant = 0xFF123524.toInt(),
        onSurface = CREAM, onSurfaceVariant = 0xFF9DBFA9.toInt(),
        line = 0xFF2E6B4A.toInt(), ok = 0xFF6FD08C.toInt(), warn = 0xFFF2C14E.toInt(), error = 0xFFFF9585.toInt(),
    )
    val Light = Palette(
        accent = 0xFF824A05.toInt(), onAccent = 0xFFFFF6DC.toInt(),
        container = 0xFFEADBB0.toInt(), onContainer = 0xFF123524.toInt(),
        background = CREAM, surface = CREAM, surfaceVariant = 0xFFFFF6DC.toInt(),
        onSurface = 0xFF123524.toInt(), onSurfaceVariant = 0xFF3F6450.toInt(),
        line = 0xFFB89F63.toInt(), ok = 0xFF1A6232.toInt(), warn = 0xFF7A4F00.toInt(), error = 0xFFA8231A.toInt(),
    )
    val NeonDark = Dark.copy(accent = 0xFF3EE6D0.toInt())
    val NeonLight = Light.copy(accent = 0xFF00685E.toInt())
}

val RetroColors: Set<Int> = setOf(Retro.LOGO_BASE, Retro.CREAM, Retro.GOLD) +
    listOf(Retro.Dark, Retro.Light, Retro.NeonDark, Retro.NeonLight).flatMap { it.all() }
