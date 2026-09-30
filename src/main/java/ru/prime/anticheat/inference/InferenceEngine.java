package ru.prime.anticheat.inference;

import ru.prime.anticheat.data.TickData;
import ru.prime.anticheat.inference.InferenceClient.InferenceResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The only engine - cloud API over WebSocket. */
public interface InferenceEngine {

    String getName();

    boolean isReady();

    CompletableFuture<InferenceResponse> predict(List<TickData> ticks, String playerName, String uuid);

    /** Update url/token/debug; reconnects only if something changed. */
    void updateConfig(String serverUrl, String authToken, boolean debug);

    void setTimeoutSec(int timeoutSec);

    void forceReconnect();

    String getServerUrl();

    long getSentRequests();

    long getReceivedResponses();

    void close();
}
