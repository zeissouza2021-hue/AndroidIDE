/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.mutwakil.androidide.preferences

import android.content.Intent
import dev.mutwakil.androidide.R
import dev.mutwakil.androidide.aiagent.ui.AiProviderSettingsActivity
import kotlinx.parcelize.Parcelize

private const val KEY_AI_AGENT = "idepref_ai_agent"
private const val KEY_AI_PROVIDERS = "idepref_ai_providers"

/**
 * Opens [AiProviderSettingsActivity] where AI providers, API keys and models are
 * configured. Strings/icons come from the `:ai-agent` module (merged into the app R).
 */
val aiProvidersPreference =
  SimpleClickablePreference(
    key = KEY_AI_PROVIDERS,
    title = R.string.aiagent_settings_title,
    summary = R.string.aiagent_settings_summary,
    icon = R.drawable.ic_aiagent_chat
  ) {
    it.context.startActivity(Intent(it.context, AiProviderSettingsActivity::class.java))
    true
  }

@Parcelize
class AiAgentPreferences(
  override val key: String = KEY_AI_AGENT,
  override val title: Int = R.string.aiagent_settings_category,
  override val children: List<IPreference> = mutableListOf()
) : IPreferenceGroup() {

  init {
    addPreference(aiProvidersPreference)
  }
}
