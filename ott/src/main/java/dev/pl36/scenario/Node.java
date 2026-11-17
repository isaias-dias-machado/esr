package dev.pl36.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * One node declared in a scenario YAML file, e.g.
 *
 * <pre>
 * - id: 0
 *   ip_addr: 10.1.15.16/24
 *   description: "n16"
 *   role: server
 * </pre>
 *
 * <p>Invariants: {@code id} is unique inside a {@link Scenario},
 * {@code ipAddr} is an IPv4 address in CIDR notation, and {@code role} is
 * never {@code null}: an omitted or explicitly null {@code role} becomes
 * {@link NodeRole#RELAY}. The first three keys are {@code required = true},
 * which is how missing keys stay parse errors while {@code role} may be
 * omitted (the parser no longer enables the blanket
 * {@code FAIL_ON_MISSING_CREATOR_PROPERTIES}, see {@link ScenarioParser}).
 * The UDP port is not part of a node: it is shared by the whole scenario and
 * lives in {@link Scenario#port()}.</p>
 *
 * <p>Failure modes: this record does no I/O. The invariants above are enforced
 * while reading the file by {@link ScenarioParser} (and by Jackson for the
 * role spelling, see {@link NodeRole}); {@link #address()} and
 * {@link #prefixLength()} throw {@link IllegalArgumentException} if the CIDR
 * text is malformed.</p>
 */
public record Node(
        @JsonProperty(value = "id", required = true) int id,
        @JsonProperty(value = "ip_addr", required = true) String ipAddr,
        @JsonProperty(value = "description", required = true) String description,
        @JsonProperty("role") NodeRole role) {

    public Node {
        // An omitted role is filled with the @JsonProperty defaultValue above;
        // an explicit `role: null` normalizes to the same RELAY default, so the
        // two spellings of "no particular role" behave identically.
        role = role == null ? NodeRole.RELAY : role;
    }

    /**
     * Purpose: expose the address part of {@code ipAddr} as a typed value, so
     * callers do not have to re-parse the CIDR string.
     *
     * @throws IllegalArgumentException if {@code ipAddr} is not a valid IPv4 CIDR string
     */
    public Inet4Address address() {
        String addressPart = splitCidr(ipAddr)[0];
        try {
            InetAddress address = InetAddress.getByName(addressPart);
            if (address instanceof Inet4Address ipv4Address) {
                return ipv4Address;
            }
            throw new IllegalArgumentException("Not an IPv4 address: " + ipAddr);
        } catch (UnknownHostException cause) {
            throw new IllegalArgumentException("Not a valid IPv4 CIDR address: " + ipAddr, cause);
        }
    }

    /**
     * Purpose: expose the prefix length part of {@code ipAddr} (the {@code 24} in
     * {@code 10.1.15.16/24}).
     *
     * @throws IllegalArgumentException if {@code ipAddr} is not a valid IPv4 CIDR string
     */
    public int prefixLength() {
        int prefixLength = Integer.parseInt(splitCidr(ipAddr)[1]);
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("IPv4 prefix length out of range 0..32: " + ipAddr);
        }
        return prefixLength;
    }

    /**
     * Splits {@code "10.1.15.16/24"} into {@code ["10.1.15.16", "24"]}.
     */
    private static String[] splitCidr(String cidr) {
        int slashIndex = cidr.indexOf('/');
        if (slashIndex < 1 || slashIndex == cidr.length() - 1) {
            throw new IllegalArgumentException("Expected an IPv4 address in CIDR form, got: " + cidr);
        }
        return new String[] {cidr.substring(0, slashIndex), cidr.substring(slashIndex + 1)};
    }
}
