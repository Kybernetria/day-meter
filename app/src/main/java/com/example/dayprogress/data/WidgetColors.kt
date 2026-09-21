package com.example.dayprogress.data

/** Read-only resolution: following a palette never overwrites the user's custom colors. */
data class WidgetColors(
    val fill: Int,
    val fillEnd: Int,
    val track: Int,
    val background: Int,
    val text: Int,
    val border: Int
) {
    companion object {
        fun resolve(prefs: AppPreferences): WidgetColors {
            if (!prefs.widgetFollowTheme) return WidgetColors(
                prefs.progressColor,
                prefs.progressGradientEndColor,
                prefs.progressUnfilledColor,
                prefs.backgroundColor,
                prefs.textColor,
                prefs.borderColor
            )
            return when (prefs.interfacePalette) {
                "violet" -> WidgetColors(
                    0xFFD0A2FF.toInt(), 0xFFFF86AE.toInt(), 0xFF3C304D.toInt(),
                    0, 0xFFEDF5FF.toInt(), 0xFF554263.toInt()
                )
                "ember" -> WidgetColors(
                    0xFFFFC078.toInt(), 0xFFD3EC88.toInt(), 0xFF44382C.toInt(),
                    0, 0xFFEDF5FF.toInt(), 0xFF62513E.toInt()
                )
                else -> WidgetColors(
                    0xFF64FCE3.toInt(), 0xFFFF71CF.toInt(), 0xFF293C47.toInt(),
                    0, 0xFFEDF5FF.toInt(), 0xFF3B525E.toInt()
                )
            }
        }
    }
}
