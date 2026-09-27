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

package dev.mutwakil.androidide.di

import android.app.Application
import dev.mutwakil.androidide.aiagent.AiAgent
import dev.mutwakil.androidide.aiagent.integration.BuildBridge
import dev.mutwakil.androidide.aiagent.integration.BuildBridgeProvider
import dev.mutwakil.androidide.aiagent.providers.AnthropicProvider
import dev.mutwakil.androidide.aiagent.providers.GeminiProvider
import dev.mutwakil.androidide.aiagent.providers.OpenAiCompatibleProvider
import dev.mutwakil.androidide.aiintegration.AiIntegration
import org.koin.dsl.module

/**
 * Koin module for the AI agent subsystem.
 *
 * The agent itself is exposed through the [AiAgent] facade (initialized once via
 * [initAiAgent]); no Koin definitions are required yet. The module is registered
 * so future agent services (view models, scoped helpers) have a home.
 */
val aiAgentModule = module {
}

/**
 * Initializes the AI development agent subsystem. Called once from
 * `IDEApplication.onCreate`.
 *
 * Registers the bundled provider plugins and wires the build bridge supplied by
 * the app's build/logs integration ([AiIntegration]).
 */
fun Application.initAiAgent() {
  AiAgent.init(this)
  AiAgent.registerProvider(OpenAiCompatibleProvider())
  AiAgent.registerProvider(AnthropicProvider())
  AiAgent.registerProvider(GeminiProvider())
  AiAgent.registerDefaultTools()
  AiAgent.buildBridgeProvider = BuildBridgeProvider { AiIntegration.buildBridge }
}
