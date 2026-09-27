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

package dev.mutwakil.androidide.aiagent.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.R
import dev.mutwakil.androidide.aiagent.providers.AiProviderPlugin

/**
 * Entry point for the AI chat UI. Launched via an explicit intent carrying
 * [AiAgent.EXTRA_PROJECT_PATH] (may be absent, in which case the chat works
 * without a project root).
 *
 * The toolbar hosts a provider dropdown (all registered providers with a
 * capability summary) and a close button.
 */
class AiChatActivity : AppCompatActivity() {

  private var providers: List<AiProviderPlugin> = emptyList()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AiAgent.init(this)
    setContentView(R.layout.activity_ai_chat)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.title = getString(R.string.aiagent_chat_title)
    setSupportActionBar(toolbar)

    val projectPath = intent.getStringExtra(AiAgent.EXTRA_PROJECT_PATH)
    if (savedInstanceState == null) {
      supportFragmentManager.beginTransaction()
        .replace(R.id.fragment_container, AiChatFragment.newInstance(projectPath))
        .commit()
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    providers = AiAgent.registry().all()

    val providerMenu = menu.addSubMenu(
      Menu.NONE, MENU_PROVIDER, Menu.NONE, getString(R.string.aiagent_provider)
    )
    val currentId = chatFragment()?.currentProviderId()
    if (providers.isEmpty()) {
      providerMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, getString(R.string.aiagent_no_providers))
        .isEnabled = false
    } else {
      providers.forEachIndexed { index, plugin ->
        val summary = CapabilityUi.summary(this, CapabilityUi.resolvedCaps(plugin))
        providerMenu.add(
          Menu.NONE, MENU_PROVIDER_ITEM_BASE + index, Menu.NONE,
          "${plugin.displayName} ($summary)"
        ).apply {
          isCheckable = true
          isChecked = plugin.id == currentId
        }
      }
    }

    menu.add(Menu.NONE, MENU_CLOSE, Menu.NONE, getString(R.string.aiagent_close))
      .setIcon(R.drawable.ic_aiagent_close)
      .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when {
      item.itemId == MENU_CLOSE -> {
        finish()
        true
      }
      item.itemId in MENU_PROVIDER_ITEM_BASE until MENU_PROVIDER_ITEM_BASE + providers.size -> {
        val plugin = providers[item.itemId - MENU_PROVIDER_ITEM_BASE]
        chatFragment()?.setActiveProvider(plugin)
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  /** Rebuilds the toolbar menu so the provider check mark follows the active provider. */
  fun refreshProviderMenu() {
    invalidateOptionsMenu()
  }

  private fun chatFragment(): AiChatFragment? =
    supportFragmentManager.findFragmentById(R.id.fragment_container) as? AiChatFragment

  companion object {
    private const val MENU_PROVIDER = 1
    private const val MENU_CLOSE = 2
    private const val MENU_PROVIDER_ITEM_BASE = 100
  }
}
