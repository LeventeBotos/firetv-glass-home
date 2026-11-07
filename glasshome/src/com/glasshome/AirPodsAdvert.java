package com.glasshome;

/**
 * Apple's "proximity pairing" Bluetooth LE advertisement, which AirPods and many Beats send while their case is open
 * or they are in use. It is what makes the iPhone's "open the case" card appear. No Android classes in here, so
 * glasshome/test can run it on a computer.
 *
 * Input: the manufacturer data for company 0x004C, i.e. the bytes AFTER the company id, starting with type 0x07:
 *   0 type (0x07)   1 length   2 0x01 (0x00 = a pairing-mode advert with no status)   3-4 model id
 *   5 status: high nibble bit 0x20 = "the advert's first bud is the right one" (flipped), low nibble = in-ear bits
 *   6 battery of the two buds, one nibble each   7 high nibble = charging bits, low nibble = case battery
 *   8 lid: bit 0x08 set = the lid is closed
 * Battery nibbles are 0-10 (tens of percent); 15 means unknown. This layout is from community reverse engineering
 * (furiousMAC/continuity, OpenPods); it is not documented by Apple, so every field is checked on the real device
 * through the "GlassAirPods" log tag.
 */
final class AirPodsAdvert {
    static final int UNKNOWN = -1;

    int model;
    String name = "AirPods";
    int left = UNKNOWN, right = UNKNOWN, box = UNKNOWN;       // percent
    boolean chargingLeft, chargingRight, chargingBox;
    boolean inEarLeft, inEarRight;
    boolean lidOpen;
    boolean pairing;                                          // in pairing mode: no battery or ear data

    private AirPodsAdvert() {
    }

    /** null if these bytes are not an AirPods advert. */
    static AirPodsAdvert parse(byte[] d) {
        if (d == null || d.length < 5 || d[0] != 0x07) {
            return null;
        }
        AirPodsAdvert a = new AirPodsAdvert();
        a.model = ((d[3] & 0xFF) << 8) | (d[4] & 0xFF);
        a.name = nameOf(a.model);
        if (d.length < 10 || d[2] == 0x00) {
            a.pairing = true;
            a.lidOpen = true;
            return a;
        }
        int status = d[5] & 0xFF;
        boolean flipped = (status & 0x20) == 0;
        int inEar = status & 0x0F;
        a.inEarLeft = (inEar & (flipped ? 0x08 : 0x02)) != 0;
        a.inEarRight = (inEar & (flipped ? 0x02 : 0x08)) != 0;

        int pods = d[6] & 0xFF;
        int first = pods >> 4, second = pods & 0x0F;
        a.left = percent(flipped ? first : second);
        a.right = percent(flipped ? second : first);

        int flags = d[7] & 0xFF;
        a.box = percent(flags & 0x0F);
        int charging = flags >> 4;
        a.chargingRight = (charging & (flipped ? 0x02 : 0x01)) != 0;
        a.chargingLeft = (charging & (flipped ? 0x01 : 0x02)) != 0;
        a.chargingBox = (charging & 0x04) != 0;

        a.lidOpen = (d[8] & 0x08) == 0;
        return a;
    }

    private static int percent(int nibble) {
        return nibble <= 10 ? nibble * 10 : UNKNOWN;
    }

    private static String nameOf(int model) {
        switch (model) {
            case 0x0220: return "AirPods";
            case 0x0F20: return "AirPods";
            case 0x1320: return "AirPods";
            case 0x1920: return "AirPods";
            case 0x0E20: return "AirPods Pro";
            case 0x1420: return "AirPods Pro";
            case 0x2420: return "AirPods Pro";
            case 0x0A20: return "AirPods Max";
            default: return "AirPods";
        }
    }

    boolean inEar() {
        return inEarLeft || inEarRight;
    }

    /** The lowest known bud level, or UNKNOWN. */
    int budsLow() {
        if (left == UNKNOWN) {
            return right;
        }
        return right == UNKNOWN ? left : Math.min(left, right);
    }

    boolean budsCharging() {
        return chargingLeft || chargingRight;
    }
}
