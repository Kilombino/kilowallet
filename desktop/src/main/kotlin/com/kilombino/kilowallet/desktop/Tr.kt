package com.kilombino.kilowallet.desktop

import com.kilombino.kilowallet.desktop.i18n.I18n

/**
 * The desktop's own strings, written in English (the Spanish next to each is the reviewed one,
 * kept beside the code; every language, Spanish included, comes from i18n-desktop/<lang>.json).
 */
object Tr {
    fun t(en: String, @Suppress("UNUSED_PARAMETER") es: String): String = I18n.tr(en)
}
