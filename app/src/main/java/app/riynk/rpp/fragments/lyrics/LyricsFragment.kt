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
package app.riynk.rpp.fragments.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.appthemehelper.util.VersionUtils
import app.riynk.rpp.R
import app.riynk.rpp.activities.tageditor.TagWriter
import app.riynk.rpp.databinding.FragmentLyricsBinding
import app.riynk.rpp.extensions.accentColor
import app.riynk.rpp.extensions.materialDialog
import app.riynk.rpp.extensions.openUrl
import app.riynk.rpp.extensions.uri
import app.riynk.rpp.fragments.base.AbsMainActivityFragment
import app.riynk.rpp.helper.MusicPlayerRemote
import app.riynk.rpp.helper.MusicProgressViewUpdateHelper
import app.riynk.rpp.lyrics.LrcView
import app.riynk.rpp.lyrics.LyricsDocument
import app.riynk.rpp.lyrics.LyricsRepository
import app.riynk.rpp.model.AudioTagInfo
import app.riynk.rpp.model.Song
import app.riynk.rpp.util.FileUtils
import app.riynk.rpp.util.LyricUtil
import app.riynk.rpp.util.PreferenceUtil
import app.riynk.rpp.util.UriUtil
import com.afollestad.materialdialogs.input.input
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.set

class LyricsFragment : AbsMainActivityFragment(R.layout.fragment_lyrics),
    MusicProgressViewUpdateHelper.Callback {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private lateinit var song: Song

    private lateinit var normalLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var editSyncedLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var cacheFile: File
    private var syncedLyrics: String = ""
    private lateinit var syncedFileUri: Uri

    private var lyricsType: LyricsType = LyricsType.NORMAL_LYRICS

    private val googleSearchLrcUrl: String
        get() {
            var baseUrl = "http://www.google.com/search?"
            var query = song.title + "+" + song.artistName
            query = "q=" + query.replace(" ", "+") + " lyrics"
            baseUrl += query
            return baseUrl
        }

    private lateinit var updateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normal lyrics launcher
        normalLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    FileUtils.copyFileToUri(requireContext(), cacheFile, song.uri)
                }
            }
        editSyncedLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    requireContext().contentResolver.openOutputStream(syncedFileUri)?.use { os ->
                        (os as FileOutputStream).channel.truncate(0)
                        os.write(syncedLyrics.toByteArray())
                        os.flush()
                    }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = Fade()
        exitTransition = Fade()
        _binding = FragmentLyricsBinding.bind(view)
        updateHelper = MusicProgressViewUpdateHelper(this, 80, 500)
        updateTitleSong()
        setupLyricsView()
        loadLyrics()

        setupWakelock()
        setupViews()
        setupToolbar()
    }

    private fun setupLyricsView() {
        val currentTextSize = PreferenceUtil.lyricsMainTextSize.toFloat()
        val normalTextSize = (currentTextSize - 4f).coerceAtLeast(12f)
        binding.normalLyrics.textSize = (currentTextSize - 4f).coerceAtLeast(12f)
        binding.lyricsView.setTextSizes(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                currentTextSize,
                resources.displayMetrics
            ),
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                normalTextSize,
                resources.displayMetrics
            )
        )
        binding.lyricsView.setLyricsLineSpacing(
            PreferenceUtil.lyricsLineSpacing * resources.displayMetrics.density
        )
        val ttmlMainSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            PreferenceUtil.lyricsMainTextSize.toFloat(),
            resources.displayMetrics
        )
        binding.ttmlLyricsView.setTextSizes(ttmlMainSize, ttmlMainSize)
        binding.ttmlLyricsView.setBackgroundVocalTextSize(
            PreferenceUtil.lyricsBackgroundTextSize.toFloat()
        )
        binding.ttmlLyricsView.setLyricsLineSpacing(
            PreferenceUtil.lyricsLineSpacing.toFloat()
        )
        binding.ttmlLyricsView.setCurrentColor(accentColor())
        binding.ttmlLyricsView.setNormalColor(0x66FFFFFF.toInt())
        binding.ttmlLyricsView.setBackgroundVocalColor(0x66FFFFFF.toInt())
        binding.ttmlLyricsView.setPlaybackRate(PreferenceUtil.lyricsPlaybackRate)
        binding.ttmlLyricsView.setOnSeekListener {
            MusicPlayerRemote.seekTo(it.toInt())
        }
        binding.lyricsView.apply {
            setCurrentColor(accentColor())
            setTimeTextColor(accentColor())
            setTimelineColor(accentColor())
            setTimelineTextColor(accentColor())
            setDraggable(true, LrcView.OnPlayClickListener {
                MusicPlayerRemote.seekTo(it.toInt())
                return@OnPlayClickListener true
            })
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
        binding.ttmlLyricsView.updateTime(progress.toLong())
    }

    private fun setupViews() {
        binding.editButton.accentColor()
        binding.editButton.setOnClickListener {
            when (lyricsType) {
                LyricsType.SYNCED_LYRICS -> {
                    editSyncedLyrics()
                }
                LyricsType.NORMAL_LYRICS -> {
                    editNormalLyrics()
                }
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateTitleSong()
        loadLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        updateTitleSong()
        loadLyrics()
    }

    private fun updateTitleSong() {
        song = MusicPlayerRemote.currentSong
        if (_binding != null) {
            binding.toolbar.title =
                if (PreferenceUtil.isRpLyrics) song.title else getString(R.string.lyrics)
            binding.toolbar.subtitle = if (PreferenceUtil.isRpLyrics) song.artistName else null
        }
    }

    private fun setupToolbar() {
        mainActivity.setSupportActionBar(binding.toolbar)
        binding.toolbar.title =
            if (PreferenceUtil.isRpLyrics) song.title else getString(R.string.lyrics)
        binding.toolbar.subtitle = if (PreferenceUtil.isRpLyrics) song.artistName else null
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupWakelock() {
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_lyrics, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(binding.toolbar)
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> {
                openUrl(googleSearchLrcUrl)
                return true
            }
            R.id.action_lyrics_speed -> {
                materialDialog().show {
                    title(text = "Kecepatan lirik")
                    input(
                        hint = "1.0",
                        prefill = PreferenceUtil.lyricsPlaybackRate.toString(),
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    ) { _, input ->
                        val rate = input.toString().toFloatOrNull()
                        if (rate != null && rate in 0.5f..2.0f) {
                            PreferenceUtil.lyricsPlaybackRate = rate
                            binding.ttmlLyricsView.setPlaybackRate(rate)
                        }
                    }
                    positiveButton(res = R.string.save)
                    negativeButton(res = android.R.string.cancel)
                }
                return true
            }
        }
        return false
    }

    @SuppressLint("CheckResult")
    private fun editNormalLyrics(lyrics: String? = null) {
        val file = File(song.data)
        val content = lyrics ?: try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        val song = song

        materialDialog().show {
            title(res = R.string.edit_normal_lyrics)
            input(
                hintRes = R.string.paste_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    if (VersionUtils.hasR()) {
                        cacheFile = TagWriter.writeTagsToFilesR(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )[0]
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                        normalLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        TagWriter.writeTagsToFiles(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadNormalLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }


    @SuppressLint("CheckResult")
    private fun editSyncedLyrics(lyrics: String? = null) {
        val syncedFile = LyricUtil.getSyncedLyricsTtmlFile(song) ?: LyricUtil.getSyncedLyricsFile(song)
        val content = lyrics ?: LyricUtil.getStringFromLrc(syncedFile)

        val song = song
        materialDialog().show {
            title(res = R.string.edit_synced_lyrics)
            input(
                hintRes = R.string.paste_timeframe_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                if (VersionUtils.hasR()) {
                    syncedLyrics = input.toString()
                    if (syncedFile?.exists() == true) {
                        syncedFileUri =
                            UriUtil.getUriFromPath(requireContext(), syncedFile.absolutePath)
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(syncedFileUri)
                            )
                        editSyncedLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                        fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                        viewLifecycleOwner.lifecycleScope.launch {
                            cacheFile = TagWriter.writeTagsToFilesR(
                                requireContext(),
                                AudioTagInfo(listOf(song.data), fieldKeyValueMap, null)
                            )[0]
                            val pendingIntent = MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                            normalLyricsLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent).build()
                            )
                        }
                    }
                } else {
                    if (syncedFile?.exists() == true) {
                        syncedFile.writeText(input.toString())
                    } else {
                        LyricUtil.writeLrc(song, input.toString())
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun loadNormalLyrics() {
        val file = File(song.data)
        val lyrics = try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        binding.normalLyrics.isVisible = !lyrics.isNullOrEmpty()
        binding.noLyricsFound.isVisible = lyrics.isNullOrEmpty()
        binding.normalLyrics.text = lyrics
    }

    private fun loadLyrics() {
        val currentSong = song
        viewLifecycleOwner.lifecycleScope.launch {
            val document = withContext(Dispatchers.IO) {
                LyricsRepository.load(currentSong)
            }

            if (_binding == null || MusicPlayerRemote.currentSong.id != currentSong.id) {
                return@launch
            }

            when (document) {
                is LyricsDocument.Ttml -> {
                    binding.ttmlLyricsView.setLyrics(document.value)
                    binding.ttmlLyricsView.isVisible = true
                    binding.ttmlLyricsView.post {
                        if (_binding != null && MusicPlayerRemote.currentSong.id == currentSong.id) {
                            binding.ttmlLyricsView.updateTime(
                                MusicPlayerRemote.songProgressMillis.toLong()
                            )
                        }
                    }
                    binding.lyricsView.isVisible = false
                    binding.normalLyrics.isVisible = false
                    binding.noLyricsFound.isVisible = false
                    lyricsType = LyricsType.SYNCED_LYRICS
                }
                is LyricsDocument.Lrc -> {
                    binding.ttmlLyricsView.setLyrics(null)
                    binding.ttmlLyricsView.isVisible = false
                    binding.lyricsView.loadLrc(document.content)
                    binding.normalLyrics.isVisible = false
                    binding.noLyricsFound.isVisible = false
                    binding.lyricsView.isVisible = true
                    lyricsType = LyricsType.SYNCED_LYRICS
                }
                LyricsDocument.None -> {
                    binding.ttmlLyricsView.setLyrics(null)
                    binding.ttmlLyricsView.isVisible = false
                    binding.lyricsView.isVisible = false
                    loadNormalLyrics()
                    lyricsType = LyricsType.NORMAL_LYRICS
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateHelper.start()
    }

    override fun onPause() {
        super.onPause()
        updateHelper.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (MusicPlayerRemote.playingQueue.isNotEmpty())
            mainActivity.expandPanel()
        _binding = null
    }

    enum class LyricsType {
        NORMAL_LYRICS,
        SYNCED_LYRICS
    }
}
