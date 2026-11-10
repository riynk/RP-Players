package app.riynk.rpp.interfaces

import android.view.View
import app.riynk.rpp.model.Genre

interface IGenreClickListener {
    fun onClickGenre(genre: Genre, view: View)
}