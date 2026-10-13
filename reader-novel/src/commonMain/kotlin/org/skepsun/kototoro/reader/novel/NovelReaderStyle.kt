package org.skepsun.kototoro.reader.novel

/** Typography limits and defaults shared by the novel readers, so a setting means the same on every platform. */
object NovelReaderLimits {
    const val DEFAULT_FONT_SIZE = 17f
    const val DEFAULT_LINE_SPACING = 1.6f
    const val DEFAULT_MARGIN = 36
    const val FONT_SIZE_STEP = 0.5f
    const val LINE_SPACING_STEP = 0.1f
    const val PARAGRAPH_SPACING_STEP = 1f
    const val MARGIN_STEP = 4
    val FONT_SIZE_RANGE = 14f..24f

    /** Line height as a multiple of the font size. */
    val LINE_SPACING_RANGE = 1.2f..2.0f

    /** Blank lines between paragraphs. */
    val PARAGRAPH_SPACING_RANGE = 0f..3f
    val MARGIN_RANGE = 12..120
}

/** Colors of a reader theme as ARGB values. */
data class NovelReaderColors(
    val background: Long,
    val text: Long,
    val secondaryText: Long,
    val chromeBackground: Long,
    val chromeText: Long,
    val highlight: Long,
    val placeholder: Long,
    val placeholderText: Long,
    val isDark: Boolean,
)

fun novelReaderColors(preset: NovelReaderThemePreset, isDarkTheme: Boolean): NovelReaderColors = when (preset) {
    NovelReaderThemePreset.PAPER -> if (isDarkTheme) {
        NovelReaderColors(
            0xFF221F1B, 0xFFDED6C9, 0xFFB7AEA0, 0xFF2D2924, 0xFFE6DED1, 0x4DB68A4A, 0xFF3B352F, 0xFFB7AEA0, true,
        )
    } else {
        NovelReaderColors(
            0xFFF4ECD8, 0xFF4F4032, 0xFF7A6A59, 0xFFE7DDC5, 0xFF544436, 0x4DA67C2E, 0xFFDDD2BC, 0xFF7A6A59, false,
        )
    }
    NovelReaderThemePreset.SEPIA -> if (isDarkTheme) {
        NovelReaderColors(
            0xFF2A241D, 0xFFE4D4B8, 0xFFB9A58A, 0xFF342D25, 0xFFEFDFC2, 0x4DBA8748, 0xFF43382D, 0xFFB9A58A, true,
        )
    } else {
        NovelReaderColors(
            0xFFF1E4CC, 0xFF5A4330, 0xFF8A7057, 0xFFE4D3B7, 0xFF614734, 0x4DA87B2C, 0xFFDCC7A6, 0xFF8A7057, false,
        )
    }
    NovelReaderThemePreset.MOSS -> if (isDarkTheme) {
        NovelReaderColors(
            0xFF1F2520, 0xFFD5DDD2, 0xFFA5B19E, 0xFF293029, 0xFFE0E7DB, 0x4D7DA062, 0xFF333B34, 0xFFA5B19E, true,
        )
    } else {
        NovelReaderColors(
            0xFFE6E9DB, 0xFF36402E, 0xFF61705A, 0xFFD6DBC8, 0xFF3D4735, 0x4D6F8A42, 0xFFC8D0BA, 0xFF61705A, false,
        )
    }
    NovelReaderThemePreset.SLATE -> if (isDarkTheme) {
        NovelReaderColors(
            0xFF1D2329, 0xFFD7DDE2, 0xFFAEB8C0, 0xFF252D34, 0xFFE3E8EC, 0x4D5F8EAD, 0xFF313A42, 0xFFAEB8C0, true,
        )
    } else {
        NovelReaderColors(
            0xFFE8EDF1, 0xFF3A4650, 0xFF66737D, 0xFFDCE4EA, 0xFF404B54, 0x4D6D93B0, 0xFFCED8DF, 0xFF66737D, false,
        )
    }
}
