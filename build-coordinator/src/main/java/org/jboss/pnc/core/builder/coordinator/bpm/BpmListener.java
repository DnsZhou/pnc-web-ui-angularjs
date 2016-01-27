/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.pnc.core.builder.coordinator.bpm;

import org.jboss.pnc.spi.BuildResult;

import java.util.function.Consumer;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
class BpmListener {
    private final long taskId;
    private final Consumer<BuildResult> onComplete;

    public BpmListener(long taskId, Consumer<BuildResult> onComplete) {
        this.taskId = taskId;
        this.onComplete = onComplete;
    }

    public long getTaskId() {
        return taskId;
    }

    public void onComplete(BuildResult buildExecutionResult) {
        onComplete.accept(buildExecutionResult);
    }
}
