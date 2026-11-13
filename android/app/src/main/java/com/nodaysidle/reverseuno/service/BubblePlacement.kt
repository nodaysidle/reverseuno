package com.nodaysidle.reverseuno.service

/**
 * Where the docked pill rests. It keeps the edge position the user dragged it to and moves up
 * only while the keyboard would cover it, to just above the keyboard (an accessibility overlay
 * draws over the IME, so it would otherwise sit on top of keys). [keyboardTop] is null when no
 * keyboard is showing.
 */
internal fun dockedY(saved: Int, minY: Int, maxY: Int, height: Int, keyboardTop: Int?, gap: Int): Int {
    val y = saved.coerceIn(minY, maxY)
    if (keyboardTop == null) return y
    val limit = keyboardTop - height - gap
    return if (y > limit) limit.coerceAtLeast(minY) else y
}

/**
 * The drop zone for drag-to-dismiss, as in Typeless: a bottom-centred rectangle 40% of the
 * screen wide. A drag ends in it when the pill's centre is inside.
 */
internal fun inDismissZone(centerX: Int, centerY: Int, screenWidth: Int, screenHeight: Int, zoneHeight: Int): Boolean {
    val halfWidth = (screenWidth * DISMISS_ZONE_WIDTH_FRACTION / 2).toInt()
    val mid = screenWidth / 2
    return centerY >= screenHeight - zoneHeight && centerX in (mid - halfWidth)..(mid + halfWidth)
}

internal const val DISMISS_ZONE_WIDTH_FRACTION = 0.4f

/**
 * A dismissed pill stays hidden in that app until you leave it. Returns the package still
 * dismissed after a window change to [windowPackage]: the system UI, the keyboard and
 * reverseuno itself do not count as leaving.
 */
internal fun dismissedAfterWindowChange(dismissed: String?, windowPackage: String, ownPackage: String): String? {
    if (dismissed == null || windowPackage.isBlank()) return dismissed
    if (windowPackage == dismissed || windowPackage == ownPackage) return dismissed
    if (windowPackage == "com.android.systemui" || windowPackage.contains("inputmethod", ignoreCase = true)) return dismissed
    return null
}
