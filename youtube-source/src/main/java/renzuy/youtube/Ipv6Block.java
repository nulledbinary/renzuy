package renzuy.youtube;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents an IPv6 CIDR block used for rotating outbound IP addresses.
 */
public final class Ipv6Block {
    private final byte[] networkPrefix;
    private final int prefixLength;

    public Ipv6Block(String cidr) {
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid CIDR format. Expected IPv6/prefix");
        }
        try {
            InetAddress addr = InetAddress.getByName(parts[0]);
            if (!(addr instanceof Inet6Address)) {
                throw new IllegalArgumentException("Not an IPv6 address: " + parts[0]);
            }
            this.networkPrefix = addr.getAddress();
            this.prefixLength = Integer.parseInt(parts[1]);
            if (this.prefixLength < 0 || this.prefixLength > 128) {
                throw new IllegalArgumentException("Invalid prefix length: " + this.prefixLength);
            }
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid IPv6 CIDR block: " + cidr, e);
        }
    }

    /**
     * Generates a random IP address within this IPv6 block.
     *
     * @return the generated InetAddress
     */
    public InetAddress generateRandom() {
        byte[] ip = new byte[16];
        System.arraycopy(networkPrefix, 0, ip, 0, 16);
        
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 16; i++) {
            int bitOffset = i * 8;
            if (bitOffset >= prefixLength) {
                ip[i] = (byte) random.nextInt(256);
            } else if (bitOffset + 8 > prefixLength) {
                int mask = 0xFF << (8 - (prefixLength - bitOffset));
                ip[i] = (byte) ((ip[i] & mask) | (random.nextInt(256) & ~mask));
            }
        }
        try {
            return Inet6Address.getByAddress(ip);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Should never happen with valid 16-byte array", e);
        }
    }

    /**
     * Generates a random IP address within this IPv6 block as a string.
     *
     * @return the string representation of the generated address
     */
    public String generateRandomString() {
        return generateRandom().getHostAddress();
    }
}
