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

package dev.mutwakil.androidide.aiagent.gate

/**
 * Thrown by provider adapters when a chat request involves an attachment or media
 * type that the active provider cannot handle for the configured model.
 *
 * Adapters must check every attachment with
 * [CapabilityGate.missingCapability] *before* building the provider request and
 * throw this with [CapabilityGate.unavailableMessage] as the message, so media is
 * never silently dropped nor sent to a provider that cannot process it.
 */
class UnsupportedCapabilityException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
