/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.crdroid.settings.fragments.misc

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Per-app pickers for the "Privacy" screen.
//
// Donor: Infinity X packages_apps_InfinitySuite@16
// (HideDeveloperStatusSettings / HideAppListStatusSettings) and the donor
// framework utils com.android.internal.util.crdroid.Hide{DeveloperStatus,AppList}Utils.
// Rime/crDroid adaptation: the donor RecyclerView screens are rebuilt with the
// Compose app-picker components already used by the spoofing screens, and the
// donor storage format (comma-separated Settings.Secure lists, written for every
// user) is preserved.
abstract class SecureAppPickerFragment : SettingsPreferenceFragment() {

    protected abstract fun storageKey(): String

    protected abstract fun headerTitleRes(): Int

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.CRDROID_SETTINGS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                SecureAppPickerContent(
                    context = requireContext(),
                    storageKey = storageKey(),
                    headerTitleRes = headerTitleRes(),
                )
            }
        }
    }
}

class HideDeveloperStatusSettings : SecureAppPickerFragment() {
    override fun storageKey() = Settings.Secure.HIDE_DEVELOPER_STATUS
    override fun headerTitleRes() = R.string.hide_developer_status_title
}

private val EXCLUDED_SUFFIXES = listOf(
    ".auto_generated", ".appsearch", ".backup", ".carrier",
    ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
    ".qti", ".qualcomm", ".resources", ".systemui.clocks",
    ".systemui.plugin", ".theme", ".iconpack",
)

private data class SecureAppState(
    val entry: AppListEntry,
)

@Composable
private fun SecureAppPickerContent(
    context: Context,
    storageKey: String,
    headerTitleRes: Int,
) {
    val scope = rememberCoroutineScope()
    val activityManager = remember {
        context.getSystemService(ActivityManager::class.java)
    }
    val userManager = remember {
        context.getSystemService(UserManager::class.java)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<SecureAppState>() }

    fun loadSelected(): Set<String> {
        val raw = Settings.Secure.getString(context.contentResolver, storageKey)
        if (raw.isNullOrEmpty() || raw == ",") return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun saveSelected(selected: List<String>) {
        val value = selected.joinToString(",")
        val users = userManager?.users
        if (users.isNullOrEmpty()) {
            Settings.Secure.putString(context.contentResolver, storageKey, value)
        } else {
            for (user in users) {
                Settings.Secure.putStringForUser(
                    context.contentResolver, storageKey, value, user.id)
            }
        }
    }

    fun persistCurrentSelection() {
        saveSelected(allApps.filter { it.entry.isSelected }.map { it.entry.packageName })
    }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val selected = loadSelected()

            val installed = filterInstalledApps(
                pm = pm,
                showSystem = showSystemApps,
                targeted = selected,
                extraFilter = { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val isSuffixExcluded = EXCLUDED_SUFFIXES.any { app.packageName.contains(it) }
                    !(isSystem && isSuffixExcluded)
                },
            )
                .sortedWith(targetedFirstComparator(pm, selected))
                .map { app ->
                    SecureAppState(
                        entry = AppListEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            isSelected = selected.contains(app.packageName),
                        ),
                    )
                }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val query = searchQuery.lowercase()
        allApps.filter { state ->
            query.isEmpty() ||
                state.entry.label.lowercase().contains(query) ||
                state.entry.packageName.lowercase().contains(query)
        }
    }

    val activeCount = allApps.count { it.entry.isSelected }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SpoofingHeaderCard(
                title = stringResource(headerTitleRes),
                subtitle = stringResource(R.string.privacy_picker_selected_count, activeCount),
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            AppPickerSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = { Text(stringResource(R.string.show_system_apps)) },
                    leadingIcon = if (showSystemApps) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        filteredApps.forEach { s ->
                            val i = allApps.indexOfFirst {
                                it.entry.packageName == s.entry.packageName }
                            if (i >= 0 && !allApps[i].entry.isSelected)
                                allApps[i] = allApps[i].copy(
                                    entry = allApps[i].entry.copy(isSelected = true))
                        }
                        scope.launch(Dispatchers.IO) { persistCurrentSelection() }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.action_select_all),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val previouslySelected = allApps
                            .filter { it.entry.isSelected }
                            .map { it.entry.packageName }
                            .toSet()
                        allApps.indices.forEach { i ->
                            allApps[i] = allApps[i].copy(
                                entry = allApps[i].entry.copy(isSelected = false))
                        }
                        scope.launch(Dispatchers.IO) {
                            persistCurrentSelection()
                            killPackages(activityManager, previouslySelected)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.action_reset),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AppListAnimatedContent(
                isLoading = isLoading,
                isEmpty = filteredApps.isEmpty(),
                hasSearchQuery = searchQuery.isNotEmpty(),
                modifier = Modifier.weight(1f),
                emptyContent = {
                    SpoofingEmptyState(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.app_picker_empty_title),
                        description = stringResource(R.string.app_picker_empty_description),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredApps, key = { it.entry.packageName }) { state ->
                        AppPickerItem(
                            packageName = state.entry.packageName,
                            label = state.entry.label,
                            icon = state.entry.icon,
                            isSystem = state.entry.isSystem,
                            checked = state.entry.isSelected,
                            onToggle = { nowEnabled ->
                                val i = allApps.indexOfFirst {
                                    it.entry.packageName == state.entry.packageName }
                                if (i >= 0) {
                                    allApps[i] = allApps[i].copy(
                                        entry = allApps[i].entry.copy(isSelected = nowEnabled),
                                    )
                                    scope.launch(Dispatchers.IO) {
                                        persistCurrentSelection()
                                        killPackages(
                                            activityManager,
                                            setOf(state.entry.packageName),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}
