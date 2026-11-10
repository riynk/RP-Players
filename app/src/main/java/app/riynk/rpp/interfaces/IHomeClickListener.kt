package app.riynk.rpp.interfaces

import app.riynk.rpp.model.Album
import app.riynk.rpp.model.Artist
import app.riynk.rpp.model.Genre

interface IHomeClickListener {
    fun onAlbumClick(album: Album)

    fun onArtistClick(artist: Artist)

    fun onGenreClick(genre: Genre)
}