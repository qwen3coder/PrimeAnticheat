package ru.prime.anticheat.ipinfo;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class IpInfoServiceTest {

    @Test
    public void proxyFlagMeansVpn() {
        IpInfoService.Verdict v = IpInfoService.decideVpn(true, false, "ISP", "Org", "AS1");
        assertTrue(v.suspect);
    }

    @Test
    public void hostingFlagMeansVpn() {
        IpInfoService.Verdict v = IpInfoService.decideVpn(false, true, "ISP", "Org", "AS1");
        assertTrue(v.suspect);
    }

    @Test
    public void providerKeywordMeansVpn() {
        IpInfoService.Verdict v = IpInfoService.decideVpn(
                false, false, "Mullvad VPN", "Org", "AS1");
        assertTrue(v.suspect);
        IpInfoService.Verdict clean = IpInfoService.decideVpn(
                false, false, "Rostelecom", "Home ISP", "AS123");
        assertFalse(clean.suspect);
    }

    @Test
    public void parsesFlatAndNestedJson() {
        String json = "{\"status\":\"success\",\"proxy\":false,\"lat\":55.7,"
                + "\"connection\":{\"isp\":\"Example ISP\",\"org\":\"Example Org\"},"
                + "\"timezone\":{\"id\":\"Europe/Moscow\"}}";
        Map<String, String> m = IpInfoService.parseFlatJson(json);
        assertEquals("success", m.get("status"));
        assertEquals("false", m.get("proxy"));
        assertEquals("Example ISP", m.get("connection.isp"));
        assertEquals("Europe/Moscow", m.get("timezone.id"));
    }

    @Test
    public void ipLiteralAndLocal() {
        assertTrue(IpInfoService.isIpLiteral("8.8.8.8"));
        assertTrue(IpInfoService.isIpLiteral("2001:4860:4860::8888"));
        assertFalse(IpInfoService.isIpLiteral("Steve"));
        assertFalse(IpInfoService.isIpLiteral(""));

        assertTrue(IpInfoService.isLocal("127.0.0.1"));
        assertTrue(IpInfoService.isLocal("10.0.0.5"));
        assertTrue(IpInfoService.isLocal("192.168.1.1"));
        assertTrue(IpInfoService.isLocal("172.16.0.1"));
        assertFalse(IpInfoService.isLocal("8.8.8.8"));
    }

    @Test
    public void stripsAsPrefix() {
        assertEquals("Hetzner Online GmbH",
                IpInfoService.stripAsPrefix("AS24940 Hetzner Online GmbH"));
        assertEquals("DigitalOcean, LLC",
                IpInfoService.stripAsPrefix("AS14061 DigitalOcean, LLC"));
        assertEquals("-", IpInfoService.stripAsPrefix("-"));
        assertEquals("-", IpInfoService.stripAsPrefix(null));
    }

    @Test
    public void parsesLocAndCountry() {
        double[] ll = IpInfoService.parseLoc("52.5200,13.4050");
        assertEquals(52.52, ll[0], 0.0001);
        assertEquals(13.405, ll[1], 0.0001);
        assertEquals("Germany", IpInfoService.countryName("DE"));
    }

    @Test
    public void ipinfoPrivacyFlagsMeanVpn() {        Map<String, String> m = IpInfoService.parseFlatJson(
                "{\"ip\":\"1.2.3.4\",\"privacy\":{\"vpn\":true,\"proxy\":false,"
                + "\"tor\":false,\"relay\":false,\"hosting\":true,\"service\":\"NordVPN\"}}");
        IpInfoService.Verdict v = IpInfoService.decideVpnIpInfo(m, "ISP", "Org", "AS1");
        assertTrue(v.suspect);
        assertTrue(v.reason.contains("NordVPN"));

        Map<String, String> tor = IpInfoService.parseFlatJson(
                "{\"privacy\":{\"vpn\":false,\"proxy\":false,\"tor\":true,"
                + "\"relay\":false,\"hosting\":false}}");
        assertTrue(IpInfoService.decideVpnIpInfo(tor, "ISP", "Org", "AS1").suspect);

        Map<String, String> hostingType = IpInfoService.parseFlatJson(
                "{\"asn\":{\"asn\":\"AS24940\",\"name\":\"Hetzner Online GmbH\",\"type\":\"hosting\"}}");
        IpInfoService.Verdict h = IpInfoService.decideVpnIpInfo(
                hostingType, "Hetzner Online GmbH", "Hetzner Online GmbH", "AS24940");
        assertTrue(h.suspect);

        Map<String, String> clean = IpInfoService.parseFlatJson(
                "{\"asn\":{\"asn\":\"AS12322\",\"name\":\"Rostelecom\",\"type\":\"isp\"},"
                + "\"privacy\":{\"vpn\":false,\"proxy\":false,\"tor\":false,"
                + "\"relay\":false,\"hosting\":false}}");
        assertFalse(IpInfoService.decideVpnIpInfo(
                clean, "Rostelecom", "Rostelecom", "AS12322").suspect);
    }

    static IpInfoService.IpInfo sample(String ip) {
        return new IpInfoService.IpInfo(ip, "Germany", "DE", "Bavaria", "Nuremberg", "-",
                49.4, 11.0, "Europe/Berlin", "Hetzner Online GmbH", "Hetzner Online GmbH",
                "AS24940 [hosting]", false, true, false, true,
                "hosting/datacenter flag", "ip-api");
    }

    @Test
    public void joinSnapshotRoundTrip() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        assertNull(IpInfoService.playerInfo(uuid));
        IpInfoService.rememberPlayer(uuid, sample("1.2.3.4"));
        assertEquals("1.2.3.4", IpInfoService.playerInfo(uuid).ip);
        IpInfoService.forgetPlayer(uuid);
        assertNull(IpInfoService.playerInfo(uuid));
    }

    @Test
    public void prefetchSkipsLocalAndNull() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        IpInfoService.prefetch("127.0.0.1", uuid);
        IpInfoService.prefetch(null, uuid);
        IpInfoService.prefetch("8.8.8.8", null);
        assertNull(IpInfoService.playerInfo(uuid));
    }

    @Test
    public void blacklistMatchesAsnAndProvider() {
        IpInfoService.IpInfo hetzner = sample("1.2.3.4");
        assertTrue(IpInfoService.matchesBlacklist(hetzner, java.util.List.of("AS24940")));
        assertTrue(IpInfoService.matchesBlacklist(hetzner, java.util.List.of("as24940")));
        assertTrue(IpInfoService.matchesBlacklist(hetzner, java.util.List.of("Hetzner")));
        assertTrue(IpInfoService.matchesBlacklist(
                hetzner, java.util.List.of("Rostelecom", "hetzner online")));
        assertFalse(IpInfoService.matchesBlacklist(hetzner, java.util.List.of("AS9009")));
        assertFalse(IpInfoService.matchesBlacklist(hetzner, java.util.List.of()));
        assertFalse(IpInfoService.matchesBlacklist(hetzner, null));
        assertFalse(IpInfoService.matchesBlacklist(null, java.util.List.of("AS24940")));
    }
}
