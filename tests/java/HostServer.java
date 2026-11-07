import com.glasshome.remote.CompanionServer;
import com.glasshome.remote.KeySink;
import com.glasshome.remote.PairingStore;
import com.glasshome.remote.PinDisplay;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs the Companion server on a computer, with the PIN written to a file and key presses printed, so a protocol
 * client (pyatv, see tests/companion_client_check.py) can be pointed at it. Usage: HostServer <pin-file>
 */
public class HostServer {
    public static void main(String[] args) throws Exception {
        final String pinFile = args[0];
        final Map<String, String> memory = new HashMap<>();
        PairingStore store = new PairingStore(new PairingStore.Storage() {
            public synchronized String get(String key) { return memory.get(key); }
            public synchronized void put(String key, String value) {
                if (value == null) { memory.remove(key); } else { memory.put(key, value); }
            }
        });
        KeySink keys = new KeySink() {
            public void down(int k) { System.out.println("KEY down " + k); }
            public void up(int k) { System.out.println("KEY up " + k); }
            public void key(int k) { System.out.println("KEY press " + k); }
        };
        PinDisplay pin = new PinDisplay() {
            public void show(String p) {
                try { Files.write(Paths.get(pinFile), p.getBytes()); } catch (Exception e) { throw new RuntimeException(e); }
                System.out.println("PIN " + p);
            }
            public void hide() { System.out.println("PIN hidden"); }
        };
        CompanionServer server = new CompanionServer(store, keys, pin, new CompanionServer.Log() {
            public void d(String m) { System.out.println("LOG " + m); }
        }, 120);
        System.out.println("PORT " + server.start(0));
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
