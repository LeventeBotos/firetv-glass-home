package com.glasshome;

/** Plain main(): run by `glasshome/build.sh --test`. Vectors are built from the layout documented in AirPodsAdvert. */
public class AirPodsAdvertTest {
    static int failed;

    static void check(String what, boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    static byte[] advert(int model, int status, int pods, int flags, int lid) {
        byte[] d = new byte[27];
        d[0] = 0x07; d[1] = 0x19; d[2] = 0x01;
        d[3] = (byte) (model >> 8); d[4] = (byte) model;
        d[5] = (byte) status; d[6] = (byte) pods; d[7] = (byte) flags; d[8] = (byte) lid;
        return d;
    }

    public static void main(String[] args) {
        // Pro, flipped (0x20 clear): first nibble = left. Left 80%, right 100%, case 50%, right bud charging, lid open, right in ear
        AirPodsAdvert a = AirPodsAdvert.parse(advert(0x0E20, 0x02, 0x8A, 0x15 | 0x00, 0x00));
        check("parsed", a != null);
        check("name", "AirPods Pro".equals(a.name));
        check("left 80", a.left == 80);
        check("right 100", a.right == 100);
        check("case 50", a.box == 50);
        check("lid open", a.lidOpen);
        check("right in ear (flipped)", a.inEarRight && !a.inEarLeft);

        // not flipped (0x20 set): first nibble = right
        AirPodsAdvert b = AirPodsAdvert.parse(advert(0x1320, 0x22, 0x8A, 0x4F, 0x08));
        check("unflipped right 80", b.right == 80 && b.left == 100);
        check("case unknown", b.box == AirPodsAdvert.UNKNOWN);
        check("case charging", b.chargingBox);
        check("lid closed", !b.lidOpen);
        check("left in ear (unflipped)", b.inEarLeft && !b.inEarRight);
        check("low is 80", b.budsLow() == 80);

        byte[] pairing = new byte[5];
        pairing[0] = 0x07; pairing[2] = 0x00; pairing[3] = 0x0E; pairing[4] = 0x20;
        check("pairing mode", AirPodsAdvert.parse(pairing).pairing);
        check("not apple audio", AirPodsAdvert.parse(new byte[]{0x10, 0x05, 0x01, 0x00, 0x00}) == null);
        check("null", AirPodsAdvert.parse(null) == null);

        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("AirPodsAdvertTest: all passed");
    }
}
