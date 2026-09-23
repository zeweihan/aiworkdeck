// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.config.AiModelProperties;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Request-scoped consent and cancellation. Never shared between conversation turns. */
public final class DecisionAssistContext {
    private final boolean enabled;
    private final Long projectId;
    private final Long userId;
    private final String conversationId;
    private final String modelId;
    private final AiModelProperties.Provider expectedChannel;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> inflight = ConcurrentHashMap.newKeySet();

    public DecisionAssistContext(boolean enabled, Long projectId, Long userId,
                                 String conversationId, String modelId,
                                 AiModelProperties.Provider expectedChannel) {
        this.enabled = enabled;
        this.projectId = projectId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.expectedChannel = expectedChannel;
    }

    public boolean enabled() { return enabled; }
    public Long projectId() { return projectId; }
    public Long userId() { return userId; }
    public String conversationId() { return conversationId; }
    public String modelId() { return modelId; }
    public AiModelProperties.Provider expectedChannel() { return expectedChannel; }
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }

    void track(Runnable canceller) {
        inflight.add(canceller);
        if (cancelled.get()) {
            canceller.run();
            inflight.remove(canceller);
        }
    }

    void clear(Runnable canceller) { inflight.remove(canceller); }

    public void cancel() {
        cancelled.set(true);
        for (Runnable canceller : inflight) canceller.run();
        inflight.clear();
    }
}
