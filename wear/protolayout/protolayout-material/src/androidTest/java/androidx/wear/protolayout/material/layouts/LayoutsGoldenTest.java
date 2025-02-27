/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.wear.protolayout.material.layouts;

import static androidx.wear.protolayout.material.RunnerUtils.SCREEN_HEIGHT;
import static androidx.wear.protolayout.material.RunnerUtils.SCREEN_WIDTH;
import static androidx.wear.protolayout.material.RunnerUtils.runSingleScreenshotTest;
import static androidx.wear.protolayout.material.layouts.TestCasesGenerator.generateTestCases;

import android.content.Context;
import android.util.DisplayMetrics;

import androidx.annotation.Dimension;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.screenshot.AndroidXScreenshotTestRule;
import androidx.wear.protolayout.DeviceParametersBuilders;
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
@LargeTest
public class LayoutsGoldenTest {
    private final LayoutElement mLayoutElement;
    private final String mExpected;

    @Rule
    public AndroidXScreenshotTestRule mScreenshotRule =
            new AndroidXScreenshotTestRule("wear/wear-protolayout-material");

    public LayoutsGoldenTest(String expected, LayoutElement layoutElement) {
        mLayoutElement = layoutElement;
        mExpected = expected;
    }

    @Dimension(unit = Dimension.DP)
    static int pxToDp(int px, float scale) {
        return (int) ((px - 0.5f) / scale);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        float scale = displayMetrics.density;

        InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getResources()
                .getDisplayMetrics()
                .setTo(displayMetrics);
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getResources()
                .getDisplayMetrics()
                .setTo(displayMetrics);

        DeviceParameters deviceParameters =
                new DeviceParameters.Builder()
                        .setScreenWidthDp(pxToDp(SCREEN_WIDTH, scale))
                        .setScreenHeightDp(pxToDp(SCREEN_HEIGHT, scale))
                        .setScreenDensity(displayMetrics.density)
                        // TODO(b/231543947): Add test cases for round screen.
                        .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_RECT)
                        .build();

        Map<String, LayoutElement> testCases = generateTestCases(context, deviceParameters, "");

        return testCases.entrySet().stream()
                .map(test -> new Object[] {test.getKey(), test.getValue()})
                .collect(Collectors.toList());
    }

    @SdkSuppress(maxSdkVersion = 32) // b/271486183
    @Test
    public void test() {
        runSingleScreenshotTest(mScreenshotRule, mLayoutElement, mExpected);
    }
}
