package org.continuouspath.justtype.testutil

import org.continuouspath.justtype.logging.DebugLogger
import org.continuouspath.justtype.logging.ExceptionLogWriter
import org.continuouspath.justtype.settings.SettingsRegistry
import org.continuouspath.justtype.settings.SettingsRepository
import org.junit.rules.ExternalResource

/**
 * Resets process-wide singletons after each test so correctness never depends
 * on which classes happen to share a forked JVM (forkEvery=40). Without this,
 * a class that skips its own reset leaks a repo/registry bound to a dead
 * Robolectric application into whichever class runs next.
 */
class ResetSingletonsRule : ExternalResource() {

	override fun after() = resetAll()

	companion object {
		/** The one reset list — fixtures that can't use the rule call this. */
		fun resetAll() {
			SettingsRepository.resetInstanceForTesting()
			SettingsRegistry.resetInstanceForTesting()
			DebugLogger.resetForTesting()
			ExceptionLogWriter.resetForTesting()
		}
	}
}
