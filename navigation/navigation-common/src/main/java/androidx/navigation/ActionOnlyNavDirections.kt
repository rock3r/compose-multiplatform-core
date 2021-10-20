/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.navigation

import android.os.Bundle

/**
 * An implementation of [NavDirections] without any arguments.
 *
 * This class should not be used directly; prefer the NavDirections classes generated by the Safe
 * Args plugin.
 */
public data class ActionOnlyNavDirections(override val actionId: Int) : NavDirections {
    override val arguments: Bundle = Bundle()

    public override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val that = other as ActionOnlyNavDirections
        return actionId == that.actionId
    }

    public override fun hashCode(): Int {
        var result = 1
        result = 31 * result + actionId
        return result
    }

    public override fun toString(): String {
        return "ActionOnlyNavDirections(actionId=$actionId)"
    }
}