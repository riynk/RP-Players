package app.riynk.rpp.model.smartplaylist

import androidx.annotation.DrawableRes
import app.riynk.rpp.R
import app.riynk.rpp.model.AbsCustomPlaylist

abstract class AbsSmartPlaylist(
    name: String,
    @DrawableRes val iconRes: Int = R.drawable.ic_queue_music
) : AbsCustomPlaylist(
    id = PlaylistIdGenerator(name, iconRes),
    name = name
)