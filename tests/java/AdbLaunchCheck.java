import com.glasshome.remote.AdbClient;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

/**
 * Launches the injector on a stick the way the app does, but from a computer: connect, start it detached, hand it the
 * token on stdin, then disconnect. Usage: AdbLaunchCheck <host> <pkcs8-key.der> <jar-on-stick> <port> <token>
 * (The key must be one the stick already trusts, so no prompt appears.)
 */
public class AdbLaunchCheck {
    public static void main(String[] a) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        KeyPair pair;
        if (a[1].equals("new")) {   // a key the stick has never seen: this is what the app does the first time
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            pair = gen.generateKeyPair();
        } else {
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(a[1]))));
            pair = new KeyPair(kf.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())), priv);
        }

        long t0 = System.currentTimeMillis();
        AdbClient adb = new AdbClient(a[0], 5555, 5000);
        adb.authenticate(pair, "check@glasshome", 60000, () -> System.out.println("(waiting for the prompt on the TV)"));
        System.out.println("authenticated in " + (System.currentTimeMillis() - t0) + " ms");

        // the shell reads the token from its terminal (so it is never on a command line) and passes it on through a pipe,
        // which, unlike the terminal, is still there after this ADB session has ended
        AdbClient.Shell sh = adb.shell("read T; echo \"$T\" | CLASSPATH=" + a[2]
                + " setsid app_process /system/bin com.glasshome.remote.Injector " + a[3]
                + " >/data/local/tmp/glassinj.out 2>&1 & sleep 5");
        sh.write(a[4] + "\n");
        System.out.println("token sent; shell output so far: " + sh.readUntil("\u0000never", 1500).trim());
        Thread.sleep(2500);
        adb.close();
        System.out.println("disconnected after " + (System.currentTimeMillis() - t0) + " ms");
    }
}
