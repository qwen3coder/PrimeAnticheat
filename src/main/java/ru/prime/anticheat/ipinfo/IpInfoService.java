package ru.prime.anticheat.ipinfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP geolocation + VPN/Proxy detection with a free default chain and an
 * optional <a href="https://ipinfo.io">ipinfo.io</a> upgrade by token.
 *
 * <p>No token (default, fully free):
 * primary ip-api.com free tier (no key, ~45 req/min) - the only free
 * source that returns {@code proxy} and {@code hosting} flags without
 * registration. HTTP is used because the free tier does not serve HTTPS.
 * Fallback: ipwho.is (free, HTTPS, geo only, no proxy flags).
 *
 * <p>With {@code ipinfo.token} set (free 50k req/month account, HTTPS):
 * primary ipinfo.io full lookup - city/region/country, organization
 * (e.g. "Hetzner Online GmbH", "DigitalOcean, LLC", "Amazon.com, Inc."),
 * ASN with type (hosting/isp/...) and {@code privacy} flags
 * (vpn/proxy/tor/relay/hosting + service name) when the plan includes them.
 * Falls back to the free chain on network/rate errors (never on a bad token -
 * that is reported so the admin fixes the config).
 *
 * <p>All network I/O must run off the main thread (see Scheduler.runAsync
 * in the command handler). Pure helpers (parsing, heuristics, IP checks)
 * are static for unit tests.
 */
public final class IpInfoService {

    private IpInfoService() {
    }

    /** Free primary endpoint (HTTP: free tier has no HTTPS). */
    public static final String IP_API_URL = "http://ip-api.com/json/";

    /** Free fallback endpoint (HTTPS, geo only). */
    public static final String IPWHO_URL = "https://ipwho.is/";

    /** Optional upgrade: full lookup with geo + org + ASN + privacy flags (needs token). */
    public static final String IPINFO_URL = "https://ipinfo.io/";

    /** ip-api.com free limit is 45 req/min - space our calls out. */
    public static final long MIN_INTERVAL_MS = 1500;

    private static volatile long lastIpApiCall;
    private static volatile int timeoutMs = 5000;
    private static volatile long cacheTtlMs = 60L * 60 * 1000;
    private static volatile String token = "";
    private static volatile String lastConfiguredToken = "";

    private static final int MAX_CACHE = 1000;

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    /** Join-time snapshot per online player: /pac ipinfo reads this, no request. */
    private static final Map<java.util.UUID, IpInfo> BY_PLAYER = new ConcurrentHashMap<>();

    private static final class Cached {
        final IpInfo info;
        final long at;

        Cached(IpInfo info, long at) {
            this.info = info;
            this.at = at;
        }
    }

    /** Lookup result (immutable). */
    public static final class IpInfo {
        public final String ip;
        public final String country;
        public final String countryCode;
        public final String region;
        public final String city;
        public final String zip;
        public final double lat;
        public final double lon;
        public final String timezone;
        public final String isp;
        public final String org;
        public final String as;
        public final boolean proxy;
        public final boolean hosting;
        public final boolean mobile;
        /** True when proxy/hosting flags or provider-name heuristics hit. */
        public final boolean vpnSuspect;
        public final String vpnReason;
        /** "ip-api" or "ipwho.is" (fallback has no proxy flags). */
        public final String source;

        public IpInfo(String ip, String country, String countryCode, String region,
                      String city, String zip, double lat, double lon, String timezone,
                      String isp, String org, String as,
                      boolean proxy, boolean hosting, boolean mobile,
                      boolean vpnSuspect, String vpnReason, String source) {
            this.ip = ip;
            this.country = country;
            this.countryCode = countryCode;
            this.region = region;
            this.city = city;
            this.zip = zip;
            this.lat = lat;
            this.lon = lon;
            this.timezone = timezone;
            this.isp = isp;
            this.org = org;
            this.as = as;
            this.proxy = proxy;
            this.hosting = hosting;
            this.mobile = mobile;
            this.vpnSuspect = vpnSuspect;
            this.vpnReason = vpnReason;
            this.source = source;
        }
    }

    public static void configure(int timeoutSec, int cacheMinutes) {
        configure(timeoutSec, cacheMinutes, token);
    }

    public static void configure(int timeoutSec, int cacheMinutes, String ipinfoToken) {
        timeoutMs = Math.max(2, timeoutSec) * 1000;
        cacheTtlMs = Math.max(1, cacheMinutes) * 60L * 1000;
        String t = ipinfoToken == null ? "" : ipinfoToken.trim();
        token = t;
        // Token added/changed/removed - cached answers came from another source.
        if (!t.equals(lastConfiguredToken)) {
            lastConfiguredToken = t;
            CACHE.clear();
        }
    }

    /** Bad/expired ipinfo.io token: must surface, never silently fall back. */
    static final class TokenAuthException extends Exception {
        TokenAuthException(String message) {
            super(message);
        }
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /** Result prefetched at join (null when not ready/failed). */
    public static IpInfo playerInfo(java.util.UUID uuid) {
        if (uuid == null) return null;
        return BY_PLAYER.get(uuid);
    }

    public static void rememberPlayer(java.util.UUID uuid, IpInfo info) {
        if (uuid == null || info == null) return;
        BY_PLAYER.put(uuid, info);
    }

    public static void forgetPlayer(java.util.UUID uuid) {
        if (uuid == null) return;
        BY_PLAYER.remove(uuid);
    }

    /**
     * One-shot prefetch for a joining player: resolves the IP once (cached
     * lookups return instantly, no request) and remembers it for /pac ipinfo.
     * Safe to call from an async thread; failures are swallowed (null result).
     */
    public static void prefetch(String ip, java.util.UUID uuid) {
        if (ip == null || uuid == null) return;
        String norm = ip.trim();
        if (norm.isEmpty() || isLocal(norm)) return;
        try {
            rememberPlayer(uuid, lookup(norm));
        } catch (Exception ignored) {
        }
    }

    public static int cacheSize() {
        return CACHE.size();
    }

    /** Cached or fresh lookup. Throws on network/API failure. */
    public static IpInfo lookup(String ip) throws Exception {
        String norm = ip == null ? "" : ip.trim();
        Cached c = CACHE.get(norm);
        if (c != null && System.currentTimeMillis() - c.at < cacheTtlMs) {
            return c.info;
        }
        IpInfo fresh;
        if (!token.isEmpty()) {
            try {
                fresh = fromIpInfo(norm, token);
            } catch (TokenAuthException auth) {
                throw auth;
            } catch (Exception primary) {
                try {
                    fresh = fromIpApi(norm);
                } catch (Exception second) {
                    fresh = fromIpWho(norm);
                }
            }
        } else {
            try {
                fresh = fromIpApi(norm);
            } catch (Exception primary) {
                // Rate-limited / offline / blocked HTTP - try the HTTPS fallback.
                fresh = fromIpWho(norm);
            }
        }
        if (CACHE.size() >= MAX_CACHE) CACHE.clear();
        CACHE.put(norm, new Cached(fresh, System.currentTimeMillis()));
        return fresh;
    }

    // --- primary: ip-api.com ---

    static IpInfo fromIpApi(String ip) throws Exception {
        throttleIpApi();
        String url = IP_API_URL + ip
                + "?fields=status,message,country,countryCode,regionName,city,zip,lat,lon,"
                + "timezone,isp,org,as,query,proxy,hosting,mobile";
        String body = httpGet(url);
        Map<String, String> m = parseFlatJson(body);
        if (!"success".equalsIgnoreCase(m.getOrDefault("status", ""))) {
            throw new IllegalStateException(m.getOrDefault("message", "ip-api error"));
        }
        boolean proxy = Boolean.parseBoolean(m.getOrDefault("proxy", "false"));
        boolean hosting = Boolean.parseBoolean(m.getOrDefault("hosting", "false"));
        boolean mobile = Boolean.parseBoolean(m.getOrDefault("mobile", "false"));
        String isp = m.getOrDefault("isp", "-");
        String org = m.getOrDefault("org", "-");
        String as = m.getOrDefault("as", "-");
        Verdict v = decideVpn(proxy, hosting, isp, org, as);
        return new IpInfo(
                m.getOrDefault("query", ip),
                m.getOrDefault("country", "-"), m.getOrDefault("countryCode", "-"),
                m.getOrDefault("regionName", "-"), m.getOrDefault("city", "-"),
                m.getOrDefault("zip", "-"),
                parseDouble(m.get("lat")), parseDouble(m.get("lon")),
                m.getOrDefault("timezone", "-"),
                isp, org, as, proxy, hosting, mobile,
                v.suspect, v.reason, "ip-api");
    }

    // --- optional upgrade: ipinfo.io by token (org + ASN type + privacy flags) ---

    static IpInfo fromIpInfo(String ip, String authToken) throws Exception {
        String url = IPINFO_URL + ip + "?token=" + urlEncode(authToken);
        String body;
        try {
            body = httpGet(url);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("401") || msg.contains("403")) {
                throw new TokenAuthException(
                        "bad ipinfo.io token (HTTP " + msg + ") - fix ipinfo.token in config.yml");
            }
            throw e;
        }
        Map<String, String> m = parseFlatJson(body);
        if (m.containsKey("error")) {
            throw new TokenAuthException("ipinfo.io: " + m.get("error"));
        }
        // Classic lookup: org is "AS24940 Hetzner Online GmbH".
        String orgName = stripAsPrefix(firstPresent(m, "org", "company.name", "asn.name"));
        String isp = firstPresent(m, "company.name", "asn.name", "org");
        if ("-".equals(isp)) isp = orgName;
        String asnCode = firstPresent(m, "asn.asn", null);
        String asnType = firstPresent(m, "asn.type", "company.type", null);
        String as = "-".equals(asnCode) ? "-" : asnCode;
        if (!"-".equals(as) && !"-".equals(asnType) && !asnType.isEmpty()) as += " [" + asnType + "]";
        if ("-".equals(as)) as = firstPresent(m, "org", null);
        double[] latLon = parseLoc(m.get("loc"));
        Verdict v = decideVpnIpInfo(m, isp, orgName, as);
        return new IpInfo(
                m.getOrDefault("ip", ip),
                countryName(m.getOrDefault("country", "-")),
                m.getOrDefault("country", "-"),
                firstPresent(m, "region", "-"),
                firstPresent(m, "city", "-"),
                firstPresent(m, "postal", "-"),
                latLon[0], latLon[1],
                firstPresent(m, "timezone", "-"),
                isp, orgName, as,
                flag(m, "privacy.proxy"), flag(m, "privacy.hosting") || flag(m, "is_hosting"),
                false,
                v.suspect, v.reason, "ipinfo.io");
    }

    /** Privacy flags first, then ASN type, then the provider-name heuristic. */
    static Verdict decideVpnIpInfo(Map<String, String> m, String isp, String org, String as) {
        String service = m.getOrDefault("privacy.service", "").trim();
        String svc = service.isEmpty() ? " (ipinfo.io)" : ": " + service;
        if (flag(m, "privacy.vpn")) return new Verdict(true, "VPN" + svc);
        if (flag(m, "privacy.proxy")) return new Verdict(true, "proxy" + svc);
        if (flag(m, "privacy.tor")) return new Verdict(true, "Tor exit node");
        if (flag(m, "privacy.relay")) return new Verdict(true, "relay" + svc);
        if (flag(m, "privacy.hosting") || flag(m, "is_hosting")) {
            return new Verdict(true, "hosting/datacenter flag");
        }
        String type = firstPresent(m, "asn.type", "company.type", null);
        if ("hosting".equalsIgnoreCase(type)) return new Verdict(true, "hosting ASN type");
        return decideVpn(false, false, isp, org, as);
    }

    /** "AS24940 Hetzner Online GmbH" -> "Hetzner Online GmbH". */
    static String stripAsPrefix(String s) {
        if (s == null) return "-";
        String v = s.trim();
        if (v.equals("-") || v.isEmpty()) return "-";
        return v.replaceFirst("(?i)^AS\\d+\\s+", "");
    }

    /** "52.52,13.40" -> [lat, lon]. */
    static double[] parseLoc(String loc) {
        if (loc == null) return new double[]{0, 0};
        String[] parts = loc.split(",");
        if (parts.length != 2) return new double[]{0, 0};
        return new double[]{parseDouble(parts[0]), parseDouble(parts[1])};
    }

    static boolean flag(Map<String, String> m, String key) {
        String v = m.get(key);
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** ipinfo.io returns a 2-letter code ("DE"); show a readable name when known. */
    static String countryName(String code) {
        if (code == null) return "-";
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.length() != 2) return code;
        try {
            Locale loc = new Locale("", c);
            String name = loc.getDisplayCountry(Locale.ENGLISH);
            if (name != null && !name.isEmpty() && !name.equalsIgnoreCase(c)) return name;
        } catch (Exception ignored) {
        }
        return code;
    }

    // --- fallback: ipwho.is (geo only) ---

    static IpInfo fromIpWho(String ip) throws Exception {
        String body = httpGet(IPWHO_URL + ip);
        Map<String, String> m = parseFlatJson(body);
        if (!"true".equalsIgnoreCase(m.getOrDefault("success", "false"))) {
            throw new IllegalStateException(m.getOrDefault("message", "ipwho.is error"));
        }
        String isp = firstPresent(m, "isp", "connection.isp");
        String org = firstPresent(m, "org", "connection.org");
        String asn = m.getOrDefault("asn", "-");
        return new IpInfo(
                ip,
                m.getOrDefault("country", "-"), m.getOrDefault("country_code", "-"),
                firstPresent(m, "region", "-"),
                m.getOrDefault("city", "-"), m.getOrDefault("postal", "-"),
                parseDouble(firstPresent(m, "latitude", null)), parseDouble(firstPresent(m, "longitude", null)),
                m.getOrDefault("timezone.id", m.getOrDefault("timezone", "-")),
                isp, org, asn,
                false, false, false, false, "", "ipwho.is");
    }

    // --- verdict ---

    static final class Verdict {
        final boolean suspect;
        final String reason;

        Verdict(boolean suspect, String reason) {
            this.suspect = suspect;
            this.reason = reason;
        }
    }

    /** Provider-name keywords that usually mean datacenter / VPN / proxy. */
    static final String[] VPN_KEYWORDS = {
        "vpn", "proxy", "tor", "hosting", "datacenter", "data center", "cloud",
        "vps", "dedicated", "server", "colocation", "mullvad", "nordvpn",
        "expressvpn", "surfshark", "protonvpn", "windscribe", "tunnel", "wireguard",
        "ovh", "hetzner", "digitalocean", "linode", "vultr", "amazon", "azure",
        "alibaba", "google cloud", "microsoft"
    };

    static Verdict decideVpn(boolean proxy, boolean hosting,
                             String isp, String org, String as) {
        if (proxy) return new Verdict(true, "proxy flag");
        if (hosting) return new Verdict(true, "hosting/datacenter flag");
        String blob = (safe(isp) + " " + safe(org) + " " + safe(as)).toLowerCase(Locale.ROOT);
        for (String kw : VPN_KEYWORDS) {
            if (blob.contains(kw)) return new Verdict(true, "provider match: " + kw);
        }
        return new Verdict(false, "");
    }

    /** ASN blacklist: entry matches AS code ("AS24940") or provider name ("Hetzner"). */
    public static boolean matchesBlacklist(IpInfo info, java.util.List<String> entries) {
        if (info == null || entries == null || entries.isEmpty()) return false;
        String hay = (safe(info.as) + " " + safe(info.org) + " " + safe(info.isp))
                .toUpperCase(Locale.ROOT);
        for (String e : entries) {
            if (e == null) continue;
            String n = e.trim().toUpperCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            if (hay.contains(n)) return true;
        }
        return false;
    }

    // --- IP helpers ---

    /** Raw IPv4/IPv6 literal (no port, no hostname)? */
    public static boolean isIpLiteral(String s) {
        if (s == null) return false;
        String v = s.trim();
        if (v.isEmpty()) return false;
        if (v.contains(":")) return v.matches("[0-9a-fA-F:.]+");
        return v.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** Local/private/loopback - external lookup is pointless. */
    public static boolean isLocal(String ip) {
        if (ip == null) return true;
        String v = ip.trim();
        if (v.isEmpty() || v.equals("127.0.0.1") || v.equals("::1") || v.equals("0.0.0.0")) return true;
        if (v.startsWith("10.") || v.startsWith("192.168.")) return true;
        if (v.startsWith("172.")) {
            try {
                int second = Integer.parseInt(v.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {
            }
        }
        String low = v.toLowerCase(Locale.ROOT);
        return low.startsWith("fc") || low.startsWith("fd") || low.startsWith("fe80");
    }

    // --- tiny HTTP + JSON (no new deps) ---

    static String httpGet(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", "PrimeAnticheat/ipinfo");
        con.setRequestProperty("Accept", "application/json");
        int code = con.getResponseCode();
        if (code == 429) throw new IllegalStateException("rate limited (429), try again later");
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            con.disconnect();
        }
        return sb.toString();
    }

    static synchronized void throttleIpApi() {
        long now = System.currentTimeMillis();
        long wait = MIN_INTERVAL_MS - (now - lastIpApiCall);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastIpApiCall = System.currentTimeMillis();
    }

    /**
     * Flat + one-level-nested JSON parser (enough for both APIs).
     * Nested keys become "parent.child" (e.g. connection.isp, timezone.id).
     */
    static Map<String, String> parseFlatJson(String json) {
        Map<String, String> out = new java.util.HashMap<>();
        parseObject(json, "", out);
        return out;
    }

    private static void parseObject(String json, String prefix, Map<String, String> out) {
        int i = 0;
        int n = json.length();
        while (i < n) {
            int k1 = json.indexOf('"', i);
            if (k1 < 0) break;
            int k2 = json.indexOf('"', k1 + 1);
            if (k2 < 0) break;
            String key = json.substring(k1 + 1, k2);
            int colon = json.indexOf(':', k2);
            if (colon < 0) break;
            int v = colon + 1;
            while (v < n && Character.isWhitespace(json.charAt(v))) v++;
            if (v >= n) break;
            char c = json.charAt(v);
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            if (c == '"') {
                int e = v + 1;
                StringBuilder sb = new StringBuilder();
                while (e < n) {
                    char ch = json.charAt(e);
                    if (ch == '\\' && e + 1 < n) {
                        sb.append(json.charAt(e + 1));
                        e += 2;
                        continue;
                    }
                    if (ch == '"') break;
                    sb.append(ch);
                    e++;
                }
                out.put(full, sb.toString());
                i = e + 1;
            } else if (c == '{') {
                int depth = 0;
                int e = v;
                boolean inStr = false;
                while (e < n) {
                    char ch = json.charAt(e);
                    if (ch == '"' && (e == 0 || json.charAt(e - 1) != '\\')) inStr = !inStr;
                    if (!inStr) {
                        if (ch == '{') depth++;
                        else if (ch == '}') {
                            depth--;
                            if (depth == 0) break;
                        }
                    }
                    e++;
                }
                parseObject(json.substring(v, Math.min(e + 1, n)), full, out);
                i = e + 1;
            } else {
                int e = v;
                while (e < n && json.charAt(e) != ',' && json.charAt(e) != '}') e++;
                out.put(full, json.substring(v, e).trim());
                i = e + 1;
            }
        }
    }

    private static String firstPresent(Map<String, String> m, String... keys) {
        for (String k : keys) {
            if (k == null) continue;
            String v = m.get(k);
            if (v != null && !v.isEmpty() && !v.equals("null")) return v;
        }
        return "-";
    }

    private static double parseDouble(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
