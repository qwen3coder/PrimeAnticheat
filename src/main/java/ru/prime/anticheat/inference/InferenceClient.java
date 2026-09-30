package ru.prime.anticheat.inference;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import ru.prime.anticheat.data.TickData;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * WebSocket client for cloud inference. Wire protocol:
 * connect to ws(s):// with a Bearer token, request
 * {id, server_name, player_name, uuid, ticks:[[dy,dp,ay,ap,jy,jp]...]},
 * response {id, error?, response:{probability}}.
 *
 * Design notes:
 *  - uuid is always sent real, never duplicated from the nickname;
 *  - server_name = PrimeAnticheat;
 *  - close() cancels pending requests (futures hung forever there);
 *  - closed flag: no zombie reconnects after close();
 *  - updateConfig reconnects only when url/token changes
 *    (there it only saved, reconnect was manual);
 *  - empty url = do not connect at all, only a warning;
 *  - request timeout is configurable (hardcoded 30s there).
 */
public class InferenceClient implements InferenceEngine {

    public static final String SERVER_NAME = "PrimeAnticheat";
    public static final String DEFAULT_TOKEN = "ip_whitelist";

    public static final int MAX_RECONNECT_DELAY_SEC = 30;

    private volatile String serverUrl;
    private volatile String authToken;
    private volatile boolean debug;
    private volatile int timeoutSec;

    private final Logger logger;
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private static final Gson SHARED_GSON = new Gson();

    private volatile boolean connected;
    private volatile boolean closed;
    private volatile WebSocket webSocket;

    private final ConcurrentHashMap<String, CompletableFuture<InferenceResponse>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong receivedCount = new AtomicLong();

    private volatile int reconnectDelaySec = 3;

    public InferenceClient(String serverUrl, String authToken, boolean debug, int timeoutSec, Logger logger) {
        this.serverUrl = serverUrl;
        this.authToken = authToken;
        this.debug = debug;
        this.timeoutSec = Math.max(5, timeoutSec);
        this.logger = logger;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        connect();
    }

    @Override
    public String getName() {
        return "API";
    }

    @Override
    public boolean isReady() {
        return connected;
    }

    @Override
    public String getServerUrl() {
        return serverUrl;
    }

    @Override
    public long getSentRequests() {
        return sentCount.get();
    }

    @Override
    public long getReceivedResponses() {
        return receivedCount.get();
    }

    @Override
    public synchronized void updateConfig(String newUrl, String newToken, boolean newDebug) {
        boolean urlChanged = newUrl != null && !newUrl.trim().isEmpty() && !newUrl.equals(serverUrl);
        boolean tokenChanged = newToken != null && !newToken.equals(authToken == null ? "" : authToken);
        if (newUrl != null && !newUrl.trim().isEmpty()) serverUrl = newUrl;
        authToken = newToken;
        debug = newDebug;
        if ((urlChanged || tokenChanged) && !closed) {
            logger.info("API config changed, reconnecting...");
            forceReconnect();
        }
    }

    @Override
    public void setTimeoutSec(int timeoutSec) {
        this.timeoutSec = Math.max(5, timeoutSec);
    }

    @Override
    public synchronized void forceReconnect() {
        if (closed) return;
        reconnectDelaySec = 1;
        connected = false;
        // The server will never answer old pending requests (we tear down the socket) - cancel them immediately,
        // otherwise player gates hang until the timeout
        clearPending("Reconnecting");
        connect();
    }

    public synchronized void connect() {
        if (closed) return;
        if (connected && webSocket != null) return;
        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Throwable ignored) {
            }
            webSocket = null;
        }
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            logger.warning("inference.url is empty - AI server not connecting. Set inference.url/token in config.yml");
            return;
        }

        String wsUrl = serverUrl.trim();
        if (wsUrl.startsWith("http://")) wsUrl = "ws://" + wsUrl.substring("http://".length());
        else if (wsUrl.startsWith("https://")) wsUrl = "wss://" + wsUrl.substring("https://".length());
        final String target = wsUrl;

        String token = (authToken != null && !authToken.trim().isEmpty()) ? authToken.trim() : DEFAULT_TOKEN;
        Request request = new Request.Builder()
                .url(target)
                .header("Authorization", "Bearer " + token)
                .build();

        logger.info("Connecting to AI server: " + target + "...");

        webSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                logger.info("AI server connected! (" + target + ")");
                connected = true;
                reconnectDelaySec = 3;
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                receivedCount.incrementAndGet();
                if (debug) logger.info("[AI-Debug] Response: " + text);
                ParsedResponse parsed;
                try {
                    parsed = parseResponse(text);
                } catch (Exception e) {
                    logger.warning("Bad AI response: " + e.getMessage());
                    return;
                }
                if (parsed.id == null) {
                    if (debug) logger.info("[AI-Debug] Response without id, ignored");
                    return;
                }
                CompletableFuture<InferenceResponse> future = pending.remove(parsed.id);
                if (future == null) {
                    if (debug) logger.info("[AI-Debug] Late/unknown response id, ignored");
                    return;
                }
                if (parsed.error != null) {
                    future.complete(new InferenceResponse(0.0, parsed.error));
                } else {
                    future.complete(new InferenceResponse(parsed.probability, null));
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if (ws != webSocket) return;
                connected = false;
                clearPending("WebSocket closed");
                logger.warning("AI connection closed: " + reason);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (ws != webSocket) return;
                connected = false;
                clearPending("WebSocket failure: " + t.getMessage());
                if (response != null && (response.code() == 401 || response.code() == 403)) {
                    logger.warning("AI server rejected auth (HTTP " + response.code()
                            + "). Check inference.token in config.yml");
                    return;
                }
                String code = response != null ? " (HTTP " + response.code() + ")" : "";
                logger.warning("AI connection error" + code + ": " + t.getMessage());
                scheduleReconnect();
            }
        });
    }

    public void clearPending(String reason) {
        pending.forEach((id, future) -> {
            if (future != null && !future.isDone()) {
                future.complete(new InferenceResponse(0.0, reason));
            }
        });
        pending.clear();
    }

    public void scheduleReconnect() {
        if (closed || scheduler.isShutdown()) return;
        scheduler.schedule(this::connect, reconnectDelaySec, TimeUnit.SECONDS);
        reconnectDelaySec = Math.min(MAX_RECONNECT_DELAY_SEC, reconnectDelaySec * 2);
    }

    @Override
    public CompletableFuture<InferenceResponse> predict(List<TickData> ticks, String playerName, String uuid) {
        if (closed) {
            return CompletableFuture.completedFuture(new InferenceResponse(0.0, "Client closed"));
        }
        if (!connected || webSocket == null) {
            return CompletableFuture.completedFuture(new InferenceResponse(0.0, "WebSocket not connected"));
        }

        String reqId = UUID.randomUUID().toString();
        CompletableFuture<InferenceResponse> future = new CompletableFuture<>();
        pending.put(reqId, future);

        try {
            String payload = buildPayload(gson, reqId, playerName, uuid, ticks);
            if (debug) {
                logger.info("[AI-Debug] Sending " + ticks.size() + " ticks for " + playerName + ": "
                        + payload.substring(0, Math.min(200, payload.length())) + "...");
            }
            if (!webSocket.send(payload)) {
                pending.remove(reqId);
                return CompletableFuture.completedFuture(new InferenceResponse(0.0, "Failed to send WS message"));
            }
            sentCount.incrementAndGet();

            scheduler.schedule(() -> {
                if (pending.remove(reqId) != null) {
                    future.complete(new InferenceResponse(0.0, "Timeout"));
                }
            }, timeoutSec, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(reqId);
            future.complete(new InferenceResponse(0.0, e.getMessage()));
        }
        return future;
    }

    /** Request body. Tick and field order match the model input order. */
    public static String buildPayload(Gson gson, String reqId, String playerName, String uuid, List<TickData> ticks) {
        JsonObject json = new JsonObject();
        json.addProperty("id", reqId);
        json.addProperty("server_name", SERVER_NAME);
        json.addProperty("player_name", playerName);
        json.addProperty("uuid", uuid);
        json.add("ticks", gson.toJsonTree(ticksToArray(ticks)));
        return json.toString();
    }

    /** Request body. Tick and field order match the model input order:
     *  dy, dp, ay, ap, jy, jp, gcdErrY, gcdErrP. */
    public static float[][] ticksToArray(List<TickData> ticks) {
        float[][] arr = new float[ticks.size()][8];
        for (int i = 0; i < ticks.size(); i++) {
            TickData t = ticks.get(i);
            arr[i][0] = sanitize(t.deltaYaw);
            arr[i][1] = sanitize(t.deltaPitch);
            arr[i][2] = sanitize(t.accelYaw);
            arr[i][3] = sanitize(t.accelPitch);
            arr[i][4] = sanitize(t.jerkYaw);
            arr[i][5] = sanitize(t.jerkPitch);
            arr[i][6] = sanitize(t.gcdErrorYaw);
            arr[i][7] = sanitize(t.gcdErrorPitch);
        }
        return arr;
    }

    public static float sanitize(float v) {
        return (Float.isNaN(v) || Float.isInfinite(v)) ? 0.0f : v;
    }

    /** Parsing of the response {id, error?, response:{probability}}. Without id - ignore (id=null). */
    public static ParsedResponse parseResponse(String text) {
        JsonObject obj = SHARED_GSON.fromJson(text, JsonObject.class);
        if (obj == null || !obj.has("id") || obj.get("id").isJsonNull()) {
            return new ParsedResponse(null, 0.0, null);
        }
        String id = obj.get("id").getAsString();
        if (obj.has("error") && !obj.get("error").isJsonNull()
                && !obj.get("error").getAsString().isEmpty()) {
            return new ParsedResponse(id, 0.0, obj.get("error").getAsString());
        }
        if (obj.has("response") && obj.get("response").isJsonObject()) {
            JsonObject resp = obj.getAsJsonObject("response");
            double prob = resp.has("probability") && !resp.get("probability").isJsonNull()
                    ? resp.get("probability").getAsDouble() : 0.0;
            return new ParsedResponse(id, prob, null);
        }
        // Fallback: flat top-level probability without the response wrapper
        if (obj.has("probability") && !obj.get("probability").isJsonNull()) {
            return new ParsedResponse(id, obj.get("probability").getAsDouble(), null);
        }
        return new ParsedResponse(id, 0.0, "Invalid response format");
    }

    /** true if the config really changed (then a reconnect is needed). */
    public static boolean configChanged(String oldUrl, String oldToken, String newUrl, String newToken) {
        String oTok = oldToken == null ? "" : oldToken;
        String nTok = newToken == null ? "" : newToken;
        boolean urlChanged = newUrl != null && !newUrl.trim().isEmpty() && !newUrl.equals(oldUrl);
        return urlChanged || !nTok.equals(oTok);
    }

    @Override
    public void close() {
        closed = true;
        connected = false;
        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Throwable ignored) {
            }
            webSocket = null;
        }
        clearPending("Client closed");
        scheduler.shutdownNow();
        try {
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        } catch (Throwable ignored) {
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public static final class ParsedResponse {
        public final String id;
        public final double probability;
        public final String error;

        public ParsedResponse(String id, double probability, String error) {
            this.id = id;
            this.probability = probability;
            this.error = error;
        }
    }

    public static class InferenceResponse {
        private final double probability;
        private final String error;

        public InferenceResponse(double probability, String error) {
            this.probability = probability;
            this.error = error;
        }

        public double getProbability() {
            return probability;
        }

        public String getError() {
            return error;
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }
    }
}
