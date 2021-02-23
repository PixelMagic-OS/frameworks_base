/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.flicker.legacysplitscreen

import android.os.Bundle
import android.platform.test.annotations.Presubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.appWindowBecomesVisible
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.launchSplitScreen
import com.android.server.wm.flicker.helpers.reopenAppFromOverview
import com.android.server.wm.flicker.navBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.startRotation
import com.android.server.wm.flicker.statusBarWindowIsAlwaysVisible
import com.android.server.wm.flicker.visibleLayersShownMoreThanOneConsecutiveEntry
import com.android.server.wm.flicker.visibleWindowsShownMoreThanOneConsecutiveEntry
import com.android.wm.shell.flicker.dockedStackDividerBecomesVisible
import com.android.wm.shell.flicker.dockedStackPrimaryBoundsIsVisible
import com.android.wm.shell.flicker.dockedStackSecondaryBoundsIsVisible
import com.android.wm.shell.flicker.helpers.SplitScreenHelper
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test open activity to primary split screen and dock secondary activity to side
 * To run this test: `atest WMShellFlickerTests:EnterSplitScreenLaunchToSide`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnterSplitScreenLaunchToSide(
    testSpec: FlickerTestParameter
) : LegacySplitScreenTransition(testSpec) {
    override val transition: FlickerBuilder.(Bundle) -> Unit
        get() = { configuration ->
            super.transition(this, configuration)
            transitions {
                device.launchSplitScreen(wmHelper)
                device.reopenAppFromOverview(wmHelper)
            }
        }

    @FlakyTest(bugId = 169271943)
    @Test
    fun dockedStackPrimaryBoundsIsVisible() =
        testSpec.dockedStackPrimaryBoundsIsVisible(testSpec.config.startRotation,
            splitScreenApp.defaultWindowName)

    @FlakyTest(bugId = 169271943)
    @Test
    fun dockedStackSecondaryBoundsIsVisible() =
        testSpec.dockedStackSecondaryBoundsIsVisible(testSpec.config.startRotation,
            secondaryApp.defaultWindowName)

    @Presubmit
    @Test
    // b/169271943
    fun dockedStackDividerBecomesVisible() = testSpec.dockedStackDividerBecomesVisible()

    @FlakyTest(bugId = 178447631)
    @Test
    // TODO(b/178447631) Remove Splash Screen from white list when flicker lib
    //                   add a wait for splash screen be gone
    fun visibleLayersShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleLayersShownMoreThanOneConsecutiveEntry(
            listOf(LAUNCHER_PACKAGE_NAME, SPLASH_SCREEN_NAME,
                splitScreenApp.defaultWindowName,
                secondaryApp.defaultWindowName)
        )

    @Presubmit
    @Test
    fun appWindowBecomesVisible() = testSpec.appWindowBecomesVisible(secondaryApp.defaultWindowName)

    @Presubmit
    @Test
    fun navBarWindowIsAlwaysVisible() = testSpec.navBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun statusBarWindowIsAlwaysVisible() = testSpec.statusBarWindowIsAlwaysVisible()

    @Presubmit
    @Test
    fun visibleWindowsShownMoreThanOneConsecutiveEntry() =
        testSpec.visibleWindowsShownMoreThanOneConsecutiveEntry(
            listOf(LAUNCHER_PACKAGE_NAME, SPLASH_SCREEN_NAME,
                splitScreenApp.defaultWindowName,
                secondaryApp.defaultWindowName)
        )

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance().getConfigNonRotationTests(
                repetitions = SplitScreenHelper.TEST_REPETITIONS,
                supportedRotations = listOf(Surface.ROTATION_0) // bugId = 175687842
            )
        }
    }
}
