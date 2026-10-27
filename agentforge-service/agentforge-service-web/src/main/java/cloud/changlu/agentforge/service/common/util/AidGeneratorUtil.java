package cloud.changlu.agentforge.service.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Agent 对外唯一标识生成器。 */
public final class AidGeneratorUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AidGeneratorUtil.class);
    private static final char[] DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v'
    };

    private static volatile AidGeneratorUtil singleton;

    private final int network;
    private long sequence;
    private long lastTimestamp;

    private AidGeneratorUtil(int network, long sequence) {
        this.network = network;
        this.sequence = sequence;
    }

    public static AidGeneratorUtil getInstance() {
        return getInstance(0, 0);
    }

    public static AidGeneratorUtil getInstance(Integer network, long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence can not less than 0");
        }
        if (network == null) {
            throw new IllegalArgumentException("network can not be null");
        }
        if (network < 0 || network > 999) {
            throw new IllegalArgumentException("network must be between 0 and 999");
        }
        if (singleton == null) {
            synchronized (AidGeneratorUtil.class) {
                if (singleton == null) {
                    singleton = new AidGeneratorUtil(network, sequence);
                    LOGGER.info("Initialized AidGeneratorUtil with network {}", network);
                }
            }
        }
        return singleton;
    }

    /** 生成下一个 AID。 */
    public synchronized String nextId() {
        long timestamp = currentSecond();
        if (timestamp < lastTimestamp) {
            LOGGER.error(
                    "Clock moved backwards. lastTimestamp {}, timestamp {}",
                    lastTimestamp,
                    timestamp);
            timestamp = lastTimestamp;
        }

        if (lastTimestamp == timestamp) {
            sequence++;
            if (sequence > 99999) {
                timestamp = waitUntilNextSecond(lastTimestamp);
                sequence = 0;
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        String rawId =
                String.valueOf(lastTimestamp)
                        .concat(String.format("%03d", network))
                        .concat(String.format("%05d", sequence));
        return toBase32(Long.parseLong(rawId));
    }

    static String toBase32(long value) {
        int shift = 5;
        int magnitude = Long.SIZE - Long.numberOfLeadingZeros(value);
        int length = Math.max((magnitude + shift - 1) / shift, 1);
        char[] buffer = new char[length];
        do {
            buffer[--length] = DIGITS[((int) value) & 31];
            value >>>= shift;
        } while (value != 0 && length > 0);
        return new String(buffer);
    }

    private long currentSecond() {
        return System.currentTimeMillis() / 1000;
    }

    private long waitUntilNextSecond(long timestamp) {
        long current = currentSecond();
        while (current <= timestamp) {
            current = currentSecond();
        }
        return current;
    }
}
