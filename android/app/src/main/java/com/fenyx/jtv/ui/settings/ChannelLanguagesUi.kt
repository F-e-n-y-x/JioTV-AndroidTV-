package com.fenyx.jtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.R
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.JtvButton
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.MainViewModel

/**
 * Settings → Channels → Channel Languages: select which languages to show or hide in the app.
 * Applies across Home, Categories, Guide, Search, and Player.
 */
@Composable
internal fun ChannelLanguagesScreen(
    modifier: Modifier,
    mainViewModel: MainViewModel,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val hiddenLangs by mainViewModel.hiddenLanguages.collectAsState()
    val langCounts = remember(mainViewModel) { mainViewModel.getAllLanguagesWithCounts() }

    // Sort languages: most channels first, then alphabetical
    val sortedLangs = remember(langCounts) {
        langCounts.keys.sortedWith(compareByDescending<String> { langCounts[it] ?: 0 }.thenBy { it })
    }

    val focus = remember { FocusRequester() }
    BackHandler { onClose() }

    val gutter = when (Jtv.form) { FormFactor.Tv -> 48.dp; FormFactor.Tablet -> 32.dp; else -> 16.dp }
    Column(modifier.fillMaxSize().background(c.bg).padding(horizontal = gutter, vertical = if (isTv) 27.dp else 8.dp)) {
        JText(stringResource(R.string.settings_channel_languages), if (isTv) 28.sp else 24.sp, weight = FontWeight.Bold)
        Text(stringResource(R.string.channel_languages_intro), style = textStyle(if (isTv) 16.sp else 15.sp), color = c.t2)
        Spacer(Modifier.height(12.dp))

        // Quick action buttons: Show all / Hide all
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            JtvButton(
                text = stringResource(R.string.channel_languages_show_all),
                onClick = { mainViewModel.setHiddenLanguages(emptySet()) },
                modifier = Modifier,
                fontSize = if (isTv) 15.sp else 14.sp,
                primary = hiddenLangs.isNotEmpty()
            )
            if (sortedLangs.size > 1) {
                JtvButton(
                    text = stringResource(R.string.channel_languages_hide_all),
                    onClick = {
                        // Keep the top language visible so the user never has 0 channels
                        val topLang = sortedLangs.firstOrNull() ?: "Hindi"
                        mainViewModel.setHiddenLanguages(sortedLangs.filter { it != topLang }.toSet())
                    },
                    modifier = Modifier,
                    fontSize = if (isTv) 15.sp else 14.sp
                )
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(sortedLangs, key = { _, lang -> lang }) { i, lang ->
                val count = langCounts[lang] ?: 0
                val isShown = lang !in hiddenLangs
                val countLabel = pluralStringResource(R.plurals.common_channel_count, count, count)
                SettingsRow(
                    label = "$lang  ($countLabel)",
                    value = stringResource(if (isShown) R.string.categories_shown else R.string.categories_hidden),
                    description = null,
                    destructive = false,
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                ) {
                    val newHidden = if (isShown) {
                        // Don't let user hide the very last remaining visible language
                        if (hiddenLangs.size >= sortedLangs.size - 1 && lang !in hiddenLangs) hiddenLangs
                        else hiddenLangs + lang
                    } else {
                        hiddenLangs - lang
                    }
                    mainViewModel.setHiddenLanguages(newHidden)
                }
            }
        }

        if (isTv) {
            Spacer(Modifier.height(6.dp))
            KeyHint(listOf(
                stringResource(R.string.settings_hint_ok_key) to stringResource(R.string.settings_hint_change),
                stringResource(R.string.common_back) to stringResource(R.string.remote_hint_back)
            ))
        }
    }
}
