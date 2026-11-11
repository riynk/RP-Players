package app.riynk.rpp.fragments.player

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import app.riynk.rpp.R
import app.riynk.rpp.SHOW_LYRICS
import app.riynk.rpp.databinding.FragmentCoverLyricsBinding
import app.riynk.rpp.extensions.dipToPix
import app.riynk.rpp.fragments.NowPlayingScreen
import app.riynk.rpp.fragments.base.AbsMusicServiceFragment
import app.riynk.rpp.fragments.base.AbsPlayerFragment
import app.riynk.rpp.fragments.base.goToLyrics
import app.riynk.rpp.helper.MusicPlayerRemote
import app.riynk.rpp.lyrics.TtmlLyrics
import app.riynk.rpp.lyrics.LyricsDocument
import app.riynk.rpp.lyrics.LyricsRepository
import app.riynk.rpp.helper.MusicProgressViewUpdateHelper
import app.riynk.rpp.model.lyrics.AbsSynchronizedLyrics
import app.riynk.rpp.model.lyrics.Lyrics
import app.riynk.rpp.util.PreferenceUtil
import app.riynk.rpp.util.color.MediaNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class CoverLyricsFragment : AbsMusicServiceFragment(R.layout.fragment_cover_lyrics),
    MusicProgressViewUpdateHelper.Callback, SharedPreferences.OnSharedPreferenceChangeListener {
    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var _binding: FragmentCoverLyricsBinding? = null
    private val binding get() = _binding!!

    private val lyricsLayout: FrameLayout get() = binding.playerLyrics
    private val lyricsLine1: TextView get() = binding.playerLyricsLine1
    private val lyricsLine2: TextView get() = binding.playerLyricsLine2

    private var lyrics: Lyrics? = null
    private var ttmlLyrics: TtmlLyrics? = null
    private var lyricsLoadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCoverLyricsBinding.bind(view)
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        if (PreferenceUtil.showLyrics) {
            progressViewUpdateHelper?.start()
        }
        binding.root.isVisible = PreferenceUtil.showLyrics
        // Remove background on Fit theme
        val nps = PreferenceUtil.nowPlayingScreen
        if (nps == NowPlayingScreen.Fit || nps == NowPlayingScreen.Full) {
            binding.root.background = null
        }
        binding.playerLyricsLine2.setOnClickListener {
            goToLyrics(requireActivity())
        }
    }

    fun setColors(color: MediaNotificationProcessor) {
        binding.run {
            playerLyrics.background = null
            playerLyricsLine1.setTextColor(color.primaryTextColor)
            playerLyricsLine1.setShadowLayer(dipToPix(10f), 0f, 0f, color.backgroundColor)
            playerLyricsLine2.setTextColor(color.primaryTextColor)
            playerLyricsLine2.setShadowLayer(dipToPix(10f), 0f, 0f, color.backgroundColor)
        }

    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == SHOW_LYRICS) {
            if (sharedPreferences.getBoolean(key, false)) {
                progressViewUpdateHelper?.start()
                binding.root.isVisible = true
                updateLyrics()
            } else {
                progressViewUpdateHelper?.stop()
                binding.root.isVisible = false
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        if (PreferenceUtil.showLyrics) {
            updateLyrics()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (PreferenceUtil.showLyrics) {
            updateLyrics()
        }
    }

    private fun updateLyrics() {
        val currentSong = MusicPlayerRemote.currentSong
        lyricsLoadJob?.cancel()
        lyricsLine1.text = null
        lyricsLine2.text = null
        lyricsLoadJob = lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                LyricsRepository.load(currentSong)
            }
            if (_binding == null || MusicPlayerRemote.currentSong.id != currentSong.id) {
                return@launch
            }

            when (document) {
                is LyricsDocument.Ttml -> {
                    ttmlLyrics = document.value
                    lyrics = null
                }
                is LyricsDocument.Lrc -> {
                    ttmlLyrics = null
                    lyrics = Lyrics.parse(currentSong, document.content)
                }
                LyricsDocument.None -> {
                    ttmlLyrics = null
                    lyrics = null
                }
            }
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        if (_binding == null) return

        if (!isLyricsLayoutVisible()) {
            hideLyricsLayout()
            return
        }

        val line = ttmlLyrics?.let { document ->
            val index = document.lines.indexOfLast { it.startMs <= progress.toLong() }
                .coerceAtLeast(0)
            document.lines.getOrNull(index)?.text.orEmpty()
        } ?: (lyrics as? AbsSynchronizedLyrics)?.getLine(progress) ?: return

        lyricsLayout.isVisible = true
        lyricsLayout.alpha = 1f

        val oldLine = lyricsLine2.text.toString()

        if (oldLine != line || oldLine.isEmpty()) {
            lyricsLine1.text = oldLine
            lyricsLine2.text = line

            lyricsLine1.isVisible = true
            lyricsLine2.isVisible = true

            lyricsLine2.measure(
                View.MeasureSpec.makeMeasureSpec(
                    lyricsLine2.measuredWidth,
                    View.MeasureSpec.EXACTLY
                ),
                View.MeasureSpec.UNSPECIFIED
            )
            val h: Float = lyricsLine2.measuredHeight.toFloat()

            lyricsLine1.alpha = 1f
            lyricsLine1.translationY = 0f
            lyricsLine1.animate().alpha(0f).translationY(-h).duration =
                AbsPlayerFragment.VISIBILITY_ANIM_DURATION

            lyricsLine2.alpha = 0f
            lyricsLine2.translationY = h
            lyricsLine2.animate().alpha(1f).translationY(0f).duration =
                AbsPlayerFragment.VISIBILITY_ANIM_DURATION
        }
    }

    private fun isLyricsLayoutVisible(): Boolean {
        return ttmlLyrics?.isValid == true || (lyrics != null && lyrics!!.isSynchronized && lyrics!!.isValid)
    }

    private fun hideLyricsLayout() {
        lyricsLayout.animate().alpha(0f).setDuration(AbsPlayerFragment.VISIBILITY_ANIM_DURATION)
            .withEndAction {
                if (_binding == null) return@withEndAction
                lyricsLayout.isVisible = false
                lyricsLine1.text = null
                lyricsLine2.text = null
            }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        progressViewUpdateHelper?.stop()
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        _binding = null
    }
}