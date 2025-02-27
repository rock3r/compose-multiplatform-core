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

package androidx.health.platform.client.impl.ipc.internal;

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;

import com.google.common.util.concurrent.SettableFuture;

/**
 * Tracker for tracking operations that are currently in progress.
 *
 * @hide
 */
@RestrictTo(Scope.LIBRARY)
public interface ExecutionTracker {

    /** Track given future as in progress. */
    void track(SettableFuture<?> future);

    /** Cancel all tracked futures with given exception. */
    void cancelPendingFutures(Throwable throwable);
}
