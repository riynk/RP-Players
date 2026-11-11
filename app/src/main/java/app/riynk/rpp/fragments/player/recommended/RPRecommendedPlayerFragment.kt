package app.riynk.rpp.fragments.player.recommended

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.preference.PreferenceManager
import app.riynk.rpp.NEW_BLUR_AMOUNT
import app.riynk.rpp.LYRICS_TYPE
import app.riynk.rpp.SHOW_LYRICS
import app.riynk.rpp.R
import app.riynk.rpp.fragments.player.normal.PlayerFragment
import app.riynk.rpp.glide.BlurTransformation
import app.riynk.rpp.glide.RetroGlideExtension
import app.riynk.rpp.glide.RetroGlideExtension.simpleSongCoverOptions
import app.riynk.rpp.glide.crossfadeListener
import app.riynk.rpp.helper.MusicPlayerRemote
import app.riynk.rpp.model.Song
import app.riynk.rpp.util.CoverLyricsType
import app.riynk.rpp.util.PreferenceUtil
import app.riynk.rpp.util.PreferenceUtil.blurAmount
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class RPRecommendedPlayerFragment : PlayerFragment(R.layout.fragment_rp_recommended_player) {
    private var backgroundImage: ImageView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backgroundImage = view.findViewById(R.id.colorBackground)
        // Keep the palette tint subtle so the artwork blur remains visible underneath.
        view.findViewById<View>(R.id.colorGradientBackground)?.alpha = 0.28f
        updateLyricsSpacer(view)
        updateBlur()
    }

    private fun updateLyricsSpacer(root: View? = view) {
        val spacer = root?.findViewById<View>(R.id.rpLyricsSpacer) ?: return
        spacer.visibility = if (
            PreferenceUtil.showLyrics && PreferenceUtil.lyricsType == CoverLyricsType.BELOW_ARTWORK
        ) View.VISIBLE else View.GONE
    }

    private fun updateBlur() {
        val target = backgroundImage ?: return
        if (!isAdded) return
        val previous = target.drawable?.constantState
            ?.newDrawable(resources)?.mutate()
            ?: ColorDrawable(Color.DKGRAY)
        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(MusicPlayerRemote.currentSong))
            .simpleSongCoverOptions(MusicPlayerRemote.currentSong)
            .transform(
                BlurTransformation.Builder(requireContext())
                    .blurRadius(blurAmount.toFloat())
                    .build()
            )
            .placeholder(previous)
            .error(ColorDrawable(Color.DKGRAY))
            .transition(DrawableTransitionOptions.withCrossFade(450))
            .into(target)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateBlur()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateBlur()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        if (key == NEW_BLUR_AMOUNT) updateBlur()
        if (key == SHOW_LYRICS || key == LYRICS_TYPE) updateLyricsSpacer()
    }

    override fun onDestroyView() {
        backgroundImage = null
        super.onDestroyView()
    }
}
