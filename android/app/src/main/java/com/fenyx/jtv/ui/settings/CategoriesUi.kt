package com.fenyx.jtv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.fenyx.jtv.R
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.FormFactor
import com.fenyx.jtv.theme.Jtv
import com.fenyx.jtv.ui.components.JText
import com.fenyx.jtv.ui.components.KeyHint
import com.fenyx.jtv.ui.components.textStyle
import com.fenyx.jtv.ui.main.Labels
import com.fenyx.jtv.ui.main.MainViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Categories: every category with Shown / Hidden. OK (or tap) opens Show/Hide, Move up,
 * Move down, Move to top. "All channels" can't be hidden. Applies to Home, Guide and the player.
 */
@Composable
internal fun CategoriesScreen(modifier: Modifier, groups: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { SettingsManager(ctx) }
    val scope = rememberCoroutineScope()
    val c = Jtv.colors
    val isTv = Jtv.isTv
    val prefs by settings.categoryPrefsFlow.collectAsState(initial = com.fenyx.jtv.data.CategoryPrefs())
    val all = remember(groups) { listOf(MainViewModel.GROUP_FAVORITES, MainViewModel.GROUP_RECENT, MainViewModel.GROUP_ALL) + groups }
    val rows = prefs.reorderAll(all)
    var picked by remember { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusKey, rows) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    BackHandler(enabled = picked == null) { onClose() }

    fun label(key: String) = when (key) {
        MainViewModel.GROUP_FAVORITES -> ctx.getString(R.string.home_cat_favourites)
        MainViewModel.GROUP_RECENT -> ctx.getString(R.string.home_cat_recent)
        MainViewModel.GROUP_ALL -> ctx.getString(R.string.home_cat_all_channels)
        else -> Labels.groupRes(key)?.let(ctx::getString) ?: key
    }
    fun save(p: com.fenyx.jtv.data.CategoryPrefs) = scope.launch { settings.setCategoryPrefs(p) }

    val gutter = when (Jtv.form) { FormFactor.Tv -> 48.dp; FormFactor.Tablet -> 32.dp; else -> 16.dp }
    Column(modifier.fillMaxSize().background(c.bg).padding(horizontal = gutter, vertical = if (isTv) 27.dp else 8.dp)) {
        JText(stringResource(R.string.settings_categories), if (isTv) 28.sp else 24.sp, weight = FontWeight.Bold)
        Text(stringResource(R.string.categories_intro), style = textStyle(if (isTv) 16.sp else 15.sp), color = c.t2)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(rows, key = { _, k -> k }) { i, key ->
                val shown = key == MainViewModel.GROUP_ALL || key !in prefs.hidden
                SettingsRow(
                    label = "${i + 1}.  ${label(key)}",
                    value = stringResource(if (shown) R.string.categories_shown else R.string.categories_hidden),
                    description = null, destructive = false,
                    modifier = if (key == (focusKey ?: rows.firstOrNull())) Modifier.focusRequester(focus) else Modifier,
                ) { picked = key }
            }
        }
        if (isTv) {
            Spacer(Modifier.height(6.dp))
            KeyHint(listOf(stringResource(R.string.settings_hint_ok_key) to stringResource(R.string.settings_hint_change), stringResource(R.string.common_back) to stringResource(R.string.remote_hint_back)))
        }
    }

    val key = picked ?: return
    val hidden = key in prefs.hidden
    val options = buildList {
        if (key != MainViewModel.GROUP_ALL) add("toggle" to ctx.getString(if (hidden) R.string.categories_show else R.string.categories_hide))
        add("up" to ctx.getString(R.string.categories_move_up))
        add("down" to ctx.getString(R.string.categories_move_down))
        add("top" to ctx.getString(R.string.categories_move_top))
    }
    PickerDialog(label(key), options, "", // an action menu: nothing is "Selected", focus starts on the first
        onSelect = { v ->
            picked = null
            focusKey = key
            save(when (v) {
                "toggle" -> prefs.toggle(key)
                "up" -> prefs.move(all, key, -1)
                "down" -> prefs.move(all, key, 1)
                else -> prefs.move(all, key, null)
            })
        },
        onDismiss = { picked = null; focusKey = key })
}
