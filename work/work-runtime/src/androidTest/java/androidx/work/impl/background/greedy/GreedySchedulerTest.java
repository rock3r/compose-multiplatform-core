/*
 * Copyright 2018 The Android Open Source Project
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

package androidx.work.impl.background.greedy;

import static androidx.work.WorkRequest.DEFAULT_BACKOFF_DELAY_MILLIS;
import static androidx.work.impl.model.WorkSpecKt.generationalId;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;
import androidx.work.Configuration;
import androidx.work.Constraints;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManagerTest;
import androidx.work.impl.Processor;
import androidx.work.impl.StartStopToken;
import androidx.work.impl.WorkLauncher;
import androidx.work.impl.constraints.WorkConstraintsTracker;
import androidx.work.impl.model.WorkSpec;
import androidx.work.worker.TestWorker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;


@RunWith(AndroidJUnit4.class)
public class GreedySchedulerTest extends WorkManagerTest {
    private Context mContext;
    private Processor mMockProcessor;
    private WorkConstraintsTracker mMockWorkConstraintsTracker;
    private GreedyScheduler mGreedyScheduler;
    private DelayedWorkTracker mDelayedWorkTracker;

    private WorkLauncher mWorkLauncher;

    @Before
    public void setUp() {
        mContext = mock(Context.class);
        mMockProcessor = mock(Processor.class);
        mMockWorkConstraintsTracker = mock(WorkConstraintsTracker.class);
        mWorkLauncher = mock(WorkLauncher.class);
        Configuration configuration = new Configuration.Builder().build();
        mGreedyScheduler = new GreedyScheduler(
                mContext, configuration,
                mMockWorkConstraintsTracker, mMockProcessor, mWorkLauncher);
        mGreedyScheduler.mInDefaultProcess = true;
        mDelayedWorkTracker = mock(DelayedWorkTracker.class);
        mGreedyScheduler.setDelayedWorkTracker(mDelayedWorkTracker);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_startsUnconstrainedWork() {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
        WorkSpec workSpec = work.getWorkSpec();
        workSpec.lastEnqueueTime = System.currentTimeMillis();
        mGreedyScheduler.schedule(workSpec);
        ArgumentCaptor<StartStopToken> captor = ArgumentCaptor.forClass(StartStopToken.class);
        verify(mWorkLauncher).startWork(captor.capture());
        assertThat(captor.getValue().getId().getWorkSpecId()).isEqualTo(workSpec.id);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_startsPeriodicWorkRequests() {
        PeriodicWorkRequest periodicWork =
                new PeriodicWorkRequest.Builder(TestWorker.class, 0L, TimeUnit.MILLISECONDS)
                        .build();
        mGreedyScheduler.schedule(periodicWork.getWorkSpec());
        // PeriodicWorkRequests are special because their periodStartTime is set to 0.
        // So the first invocation will always result in startWork(). Subsequent runs will
        // use `delayedStartWork()`.
        ArgumentCaptor<StartStopToken> captor = ArgumentCaptor.forClass(StartStopToken.class);
        verify(mWorkLauncher).startWork(captor.capture());
        assertThat(captor.getValue().getId().getWorkSpecId()).isEqualTo(periodicWork.getStringId());
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_startsDelayedWork() {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class)
                .setLastEnqueueTime(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setInitialDelay(1000L, TimeUnit.MILLISECONDS)
                .build();
        mGreedyScheduler.schedule(work.getWorkSpec());
        verify(mDelayedWorkTracker).schedule(eq(work.getWorkSpec()), anyLong());
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_startsBackedOffWork() {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class)
                .setLastEnqueueTime(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setInitialRunAttemptCount(5)
                .build();
        mGreedyScheduler.schedule(work.getWorkSpec());
        verify(mDelayedWorkTracker).schedule(eq(work.getWorkSpec()), anyLong());
    }

    @Test
    @SmallTest
    @SdkSuppress(minSdkVersion = 23)
    public void testGreedyScheduler_ignoresIdleWorkConstraint() {
        Constraints constraints = new Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class)
                .setConstraints(constraints)
                .build();
        mGreedyScheduler.schedule(work.getWorkSpec());
        verify(mMockWorkConstraintsTracker, never()).replace(ArgumentMatchers.<WorkSpec>anyList());
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_startsWorkWhenConstraintsMet() {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
        mGreedyScheduler.onAllConstraintsMet(Collections.singletonList(work.getWorkSpec()));
        ArgumentCaptor<StartStopToken> captor = ArgumentCaptor.forClass(StartStopToken.class);
        verify(mWorkLauncher).startWork(captor.capture());
        assertThat(captor.getValue().getId().getWorkSpecId()).isEqualTo(work.getWorkSpec().id);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_stopsWorkWhenConstraintsNotMet() {
        // in order to stop the work, we should start it first.
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
        mGreedyScheduler.onAllConstraintsMet(Collections.singletonList(work.getWorkSpec()));
        mGreedyScheduler.onAllConstraintsNotMet(Collections.singletonList(work.getWorkSpec()));
        ArgumentCaptor<StartStopToken> captor = ArgumentCaptor.forClass(StartStopToken.class);
        verify(mWorkLauncher).stopWork(captor.capture());
        assertThat(captor.getValue().getId().getWorkSpecId()).isEqualTo(work.getWorkSpec().id);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_constraintsAreAddedAndRemovedForTracking() {
        final OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class)
                .setConstraints(new Constraints.Builder().setRequiresCharging(true).build())
                .build();
        final WorkSpec workSpec = work.getWorkSpec();
        workSpec.lastEnqueueTime = System.currentTimeMillis();
        Set<WorkSpec> expected = new HashSet<WorkSpec>();
        expected.add(workSpec);

        mGreedyScheduler.schedule(workSpec);
        verify(mMockWorkConstraintsTracker).replace(expected);
        reset(mMockWorkConstraintsTracker);

        mGreedyScheduler.onExecuted(generationalId(workSpec), false);
        verify(mMockWorkConstraintsTracker).replace(Collections.<WorkSpec>emptySet());
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_executionListenerIsRegistered() {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
        WorkSpec workSpec = work.getWorkSpec();
        mGreedyScheduler.schedule(workSpec);
        verify(mMockProcessor).addExecutionListener(mGreedyScheduler);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_executionListenerIsRegisteredOnlyOnce() {
        for (int i = 0; i < 2; ++i) {
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
            WorkSpec workSpec = work.getWorkSpec();
            mGreedyScheduler.schedule(workSpec);
        }
        verify(mMockProcessor, times(1)).addExecutionListener(mGreedyScheduler);
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_ignoresRequestsInADifferentProcess() {
        // Context.getSystemService() returns null so no work should be executed.
        mGreedyScheduler.mInDefaultProcess = false;
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class).build();
        WorkSpec workSpec = work.getWorkSpec();
        mGreedyScheduler.schedule(workSpec);
        verify(mMockProcessor, times(0)).addExecutionListener(mGreedyScheduler);
        verify(mMockWorkConstraintsTracker, never()).replace(ArgumentMatchers.<WorkSpec>anyList());
    }

    @Test
    @SmallTest
    public void testGreedyScheduler_throttleWork() {
        long before = System.currentTimeMillis();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(TestWorker.class)
                .setLastEnqueueTime(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setInitialDelay(1000L, TimeUnit.MILLISECONDS)
                .build();
        WorkSpec workSpec = work.getWorkSpec();
        mGreedyScheduler.schedule(workSpec);
        mGreedyScheduler.onExecuted(generationalId(workSpec), true);
        WorkSpec updatedRunAttemptCount = new WorkSpec(workSpec.id, workSpec);
        updatedRunAttemptCount.runAttemptCount = 10;
        reset(mDelayedWorkTracker);
        mGreedyScheduler.schedule(updatedRunAttemptCount);
        ArgumentCaptor<Long> delayCapture = ArgumentCaptor.forClass(Long.class);
        verify(mDelayedWorkTracker).schedule(eq(updatedRunAttemptCount), delayCapture.capture());
        assertThat(delayCapture.getValue()).isAtLeast(before + 5 * DEFAULT_BACKOFF_DELAY_MILLIS);
    }
}
