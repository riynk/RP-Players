/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package app.riynk.rpp.fragments.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.launch
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import app.riynk.rpp.LYRICS_TYPE
import app.riynk.rpp.R
import app.riynk.rpp.SHOW_LYRICS
import app.riynk.rpp.adapter.album.AlbumCoverPagerAdapter
import app.riynk.rpp.adapter.album.AlbumCoverPagerAdapter.AlbumCoverFragment
import app.riynk.rpp.databinding.FragmentPlayerAlbumCoverBinding
import app.riynk.rpp.extensions.isColorLight
import app.riynk.rpp.extensions.surfaceColor
import app.riynk.rpp.fragments.NowPlayingScreen.*
import app.riynk.rpp.fragments.base.AbsMusicServiceFragment
import app.riynk.rpp.fragments.base.goToLyrics
import app.riynk.rpp.helper.MusicPlayerRemote
import app.riynk.rpp.helper.MusicProgressViewUpdateHelper
import app.riynk.rpp.lyrics.CoverLrcView
import app.riynk.rpp.lyrics.LyricsDocument
import app.riynk.rpp.lyrics.LyricsRepository
import app.riynk.rpp.model.lyrics.Lyrics
import app.riynk.rpp.transform.CarousalPagerTransformer
import app.riynk.rpp.transform.ParallaxPagerTransformer
import app.riynk.rpp.util.CoverLyricsType
import app.riynk.rpp.util.PreferenceUtil
import app.riynk.rpp.util.color.MediaNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class PlayerAlbumCoverFragment : AbsMusicServiceFragment(R.layout.fragment_player_album_cover),
    ViewPager.OnPageChangeListener, MusicProgressViewUpdateHelper.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlayerAlbumCoverBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var currentPosition: Int = 0
    val viewPager get() = binding.viewPager

    private val colorReceiver = object : AlbumCoverFragment.ColorReceiver {
        override fun onColorReady(color: MediaNotificationProcessor, request: Int) {
            if (currentPosition == request) {
                notifyColorChange(color)
            }
        }
    }
    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var lyricsLoadJob: Job? = null
    private var usingTtmlLyrics = false

    private val lrcView: CoverLrcView get() = binding.lyricsView

    var lyrics: Lyrics? = null

    fun removeSlideEffect() {
        val transformer = ParallaxPagerTransformer(R.id.player_image)
        transformer.setSpeed(0.3f)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewPager.setPageTransformer(false, transformer)
            }
        }
    }

    private fun updateLyrics() {
        val currentSong = MusicPlayerRemote.currentSong
        lyricsLoadJob?.cancel()
        lyricsLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                LyricsRepository.load(currentSong)
            }
            if (_binding == null || MusicPlayerRemote.currentSong.id != currentSong.id) {
                return@launch
            }

            when (document) {
                is LyricsDocument.Ttml -> {
                    usingTtmlLyrics = true
                    lrcView.reset()
                    binding.ttmlLyricsView.apply {
                        setTextSizes(
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics
                            ),
                            TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics
                            )
                        )
                        setCurrentColor(android.graphics.Color.WHITE)
                        setNormalColor(0x99FFFFFF.toInt())
                        setBackgroundVocalColor(0xCCFFFFFF.toInt())
                        setPlaybackRate(PreferenceUtil.lyricsPlaybackRate)
                        setOnSeekListener { time ->
                            MusicPlayerRemote.seekTo(time.toInt())
                            MusicPlayerRemote.resumePlaying()
                        }
                        setLyrics(document.value)
                        isVisible = PreferenceUtil.showLyrics &&
                            PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
                        post { updateTime(MusicPlayerRemote.songProgressMillis.toLong()) }
                    }
                    lrcView.isVisible = false
                }
                is LyricsDocument.Lrc -> {
                    usingTtmlLyrics = false
                    binding.ttmlLyricsView.setLyrics(null)
                    binding.ttmlLyricsView.isVisible = false
                    lrcView.loadLrc(document.content)
                    lrcView.isVisible = PreferenceUtil.showLyrics &&
                        PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
                }
                LyricsDocument.None -> {
                    usingTtmlLyrics = false
                    binding.ttmlLyricsView.setLyrics(null)
                    binding.ttmlLyricsView.isVisible = false
                    lrcView.reset()
                    lrcView.setLabel(context?.getString(R.string.no_lyrics_found))
                    lrcView.isVisible = PreferenceUtil.showLyrics &&
                        PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
                }
            }
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
        binding.ttmlLyricsView.updateTime(progress.toLong())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerAlbumCoverBinding.bind(view)
        setupViewPager()
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        maybeInitLyrics()
        lrcView.apply {
            setDraggable(true) { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
                true
            }
            setOnClickListener {
                goToLyrics(requireActivity())
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.addOnPageChangeListener(this)
        val nps = PreferenceUtil.nowPlayingScreen

        if (nps == Full || nps == Classic || nps == Fit || nps == Gradient) {
            binding.viewPager.offscreenPageLimit = 2
        } else if (PreferenceUtil.isCarouselEffect) {
            val metrics = resources.displayMetrics
            val ratio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
            binding.viewPager.clipToPadding = false
            val padding =
                if (ratio >= 1.777f) {
                    40
                } else {
                    100
                }
            binding.viewPager.setPadding(padding, 0, padding, 0)
            binding.viewPager.pageMargin = 0
            binding.viewPager.setPageTransformer(false, CarousalPagerTransformer(requireContext()))
        } else {
            binding.viewPager.offscreenPageLimit = 2
            binding.viewPager.setPageTransformer(
                true,
                PreferenceUtil.albumCoverTransform
            )
        }
    }

    override fun onResume() {
        super.onResume()
        maybeInitLyrics()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        binding.viewPager.removeOnPageChangeListener(this)
        progressViewUpdateHelper?.stop()
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        _binding = null
    }

    override fun onServiceConnected() {
        updatePlayingQueue()
        updateLyrics()
    }

    override fun onPlayingMetaChanged() {
        if (viewPager.currentItem != MusicPlayerRemote.position) {
            viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        }
        updateLyrics()
    }

    override fun onQueueChanged() {
        updatePlayingQueue()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SHOW_LYRICS -> {
                if (PreferenceUtil.showLyrics) {
                    maybeInitLyrics()
                } else {
                    showLyrics(false)
                    progressViewUpdateHelper?.stop()
                }
            }
            LYRICS_TYPE -> {
                maybeInitLyrics()
            }
        }
    }

    private fun setLRCViewColors(@ColorInt primaryColor: Int, @ColorInt secondaryColor: Int) {
        lrcView.apply {
            setCurrentColor(primaryColor)
            setTimeTextColor(primaryColor)
            setTimelineColor(primaryColor)
            setNormalColor(secondaryColor)
            setTimelineTextColor(primaryColor)
        }
    }

    private fun showLyrics(visible: Boolean) {
        binding.coverLyrics.isVisible = false
        binding.lyricsView.isVisible = false
        binding.ttmlLyricsView.isVisible = false
        binding.viewPager.isVisible = true

        if (PreferenceUtil.lyricsType == CoverLyricsType.BELOW_ARTWORK) {
            val belowArtworkHeight = (112 * resources.displayMetrics.density).toInt()
            binding.root.clipChildren = false
            binding.root.clipToPadding = false
            var parent = binding.root.parent
            while (parent is ViewGroup) {
                parent.clipChildren = false
                parent.clipToPadding = false
                parent = parent.parent
            }
            val belowArtworkParams = binding.coverLyrics.layoutParams as FrameLayout.LayoutParams
            belowArtworkParams.height =
                if (visible) belowArtworkHeight else ViewGroup.LayoutParams.MATCH_PARENT
            belowArtworkParams.gravity = if (visible) Gravity.TOP else Gravity.CENTER_VERTICAL
            binding.coverLyrics.layoutParams = belowArtworkParams
            binding.coverLyrics.translationY =
                if (visible) binding.root.height.toFloat() else 0f
            binding.coverLyrics.isVisible = visible
            if (visible) {
                binding.root.post {
                    if (_binding != null && PreferenceUtil.lyricsType == CoverLyricsType.BELOW_ARTWORK) {
                        binding.coverLyrics.translationY = binding.root.height.toFloat()
                        (binding.root.parent as? View)?.bringToFront()
                    }
                }
            }
            return
        }

        binding.coverLyrics.translationY = 0f
        val coverLyricsParams = binding.coverLyrics.layoutParams as FrameLayout.LayoutParams
        coverLyricsParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        coverLyricsParams.gravity = Gravity.CENTER_VERTICAL
        binding.coverLyrics.layoutParams = coverLyricsParams
        val lyrics: View = if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, if (visible) 0F else 1F).start()
            if (usingTtmlLyrics) binding.ttmlLyricsView else lrcView
        } else {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, 1F).start()
            binding.coverLyrics
        }
        ObjectAnimator.ofFloat(lyrics, View.ALPHA, if (visible) 1F else 0F).apply {
            doOnEnd {
                lyrics.isVisible = visible
            }
            start()
        }
    }

    private fun maybeInitLyrics() {
        val nps = PreferenceUtil.nowPlayingScreen
        // Don't show lyrics container for below conditions
        if (lyricViewNpsList.contains(nps) && PreferenceUtil.showLyrics) {
            showLyrics(true)
            if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
                progressViewUpdateHelper?.start()
            }
        } else {
            showLyrics(false)
            progressViewUpdateHelper?.stop()
        }
    }

    private fun updatePlayingQueue() {
        val adapter = binding.viewPager.adapter
        if (adapter is AlbumCoverPagerAdapter) {
            adapter.updateData(MusicPlayerRemote.playingQueue)
        } else {
            binding.viewPager.adapter = AlbumCoverPagerAdapter(
                parentFragmentManager,
                MusicPlayerRemote.playingQueue
            )

        }
        binding.viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        onPageSelected(MusicPlayerRemote.position)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        currentPosition = position
        if (binding.viewPager.adapter != null) {
            (binding.viewPager.adapter as AlbumCoverPagerAdapter).receiveColor(
                colorReceiver,
                position
            )
        }
        if (position != MusicPlayerRemote.position) {
            MusicPlayerRemote.playSongAt(position)
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
    }


    private fun notifyColorChange(color: MediaNotificationProcessor) {
        callbacks?.onColorChanged(color)
        val primaryColor = MaterialValueHelper.getPrimaryTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )
        val secondaryColor = MaterialValueHelper.getSecondaryDisabledTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )

        when (PreferenceUtil.nowPlayingScreen) {
            Flat, Normal, Material -> if (PreferenceUtil.isAdaptiveColor) {
                setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            } else {
                setLRCViewColors(primaryColor, secondaryColor)
            }
            Color, Classic -> setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            Blur -> setLRCViewColors(android.graphics.Color.WHITE, ColorUtil.withAlpha(android.graphics.Color.WHITE, 0.5f))
            else -> setLRCViewColors(primaryColor, secondaryColor)
        }
    }

    fun setCallbacks(listener: Callbacks) {
        callbacks = listener
    }

    interface Callbacks {

        fun onColorChanged(color: MediaNotificationProcessor)

        fun onFavoriteToggled()
    }

    companion object {
        val TAG: String = PlayerAlbumCoverFragment::class.java.simpleName
    }

    private val lyricViewNpsList =
        listOf(Blur, Classic, Color, Flat, Material, MD3, Normal, Plain, Simple, RPRecommended)
}
