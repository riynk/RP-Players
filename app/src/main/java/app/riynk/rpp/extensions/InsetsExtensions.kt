package app.riynk.rpp.extensions

import androidx.core.view.WindowInsetsCompat
import app.riynk.rpp.util.PreferenceUtil
import app.riynk.rpp.util.RetroUtil

fun WindowInsetsCompat?.getBottomInsets(): Int {
    return if (PreferenceUtil.isFullScreenMode) {
        return 0
    } else {
        this?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: RetroUtil.navigationBarHeight
    }
}
