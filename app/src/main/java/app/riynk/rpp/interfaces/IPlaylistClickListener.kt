package app.riynk.rpp.interfaces

import android.view.View
import app.riynk.rpp.db.PlaylistWithSongs

interface IPlaylistClickListener {
    fun onPlaylistClick(playlistWithSongs: PlaylistWithSongs, view: View)
}