package ir.smartremote.app.ir;

/**
 * Ported directly from org.twinone.irremote.ir.SignalFactory (divested-mobile/irremote).
 */
public class SignalFactory {

    public static Signal parse(String signal) {
        final int format = getFormat(signal);
        if (format == Signal.FORMAT_UNKNOWN) {
            throw new IllegalArgumentException("Could not parse signal");
        }
        return parse(format, signal);
    }

    private static int getFormat(String signal) {
        if (signal == null || signal.trim().isEmpty()) {
            return Signal.FORMAT_UNKNOWN;
        }
        if (signal.trim().startsWith("0000")) {
            return Signal.FORMAT_PRONTO;
        }
        return Signal.FORMAT_GLOBALCACHE;
    }

    public static Signal parse(int format, String signal) {
        switch (format) {
            case Signal.FORMAT_AUTO:
                return parse(signal);
            case Signal.FORMAT_PRONTO:
                return fromPronto(signal);
            case Signal.FORMAT_GLOBALCACHE:
                return fromGlobalCache(signal);
            default:
                throw new IllegalArgumentException("Invalid format");
        }
    }

    private static Signal fromGlobalCache(String in) {
        in = in.trim();
        final String[] split = in.split(",");
        final long[] values = new long[split.length];
        for (int i = 0; i < split.length; i++) {
            values[i] = Long.parseLong(split[i].trim());
        }
        int freq = (int) values[0];
        final int offset = 1;
        final int[] pattern = new int[values.length - offset];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (int) values[offset + i];
            if (pattern[i] <= 0) pattern[i] = 1;
        }
        return new Signal(freq, pattern);
    }

    private static Signal fromPronto(String in) {
        in = in.trim();
        final String[] split = in.split("\\s+");
        if (split.length < 6) {
            throw new IllegalArgumentException("Pronto code too short");
        }
        final long[] pronto = new long[split.length];
        for (int i = 0; i < split.length; i++) {
            pronto[i] = Long.parseLong(split[i], 16);
        }
        if (pronto[0] != 0) {
            throw new IllegalArgumentException("Invalid pronto code header");
        }
        if (pronto[1] <= 0) {
            throw new IllegalArgumentException("Zero carrier word in pronto code");
        }

        int freq = (int) (1000000 / (pronto[1] * 0.241246));
        final int bps1 = (int) pronto[2] * 2;
        final int bps2 = (int) pronto[3] * 2;
        final int offset = 4;
        final int len = Math.min(bps1 + bps2, pronto.length - offset);
        if (len <= 0) {
            throw new IllegalArgumentException("Empty pronto burst");
        }

        final int[] pattern = new int[len];
        for (int i = 0; i < pattern.length; i++) {
            pattern[i] = (int) pronto[offset + i];
            if (pattern[i] <= 0) pattern[i] = 1;
        }
        return new Signal(freq, pattern);
    }
}
