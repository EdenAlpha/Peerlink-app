import com.peerlink.app.network.GameplayPathPolicy;
import java.util.List;

/** Executes production policy code, without Android mocks or a network. */
public final class GameplayPathPolicyRegression {
    private static int checks;
    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) {
        String ip = "192.168.43.2";
        String first = GameplayPathPolicy.identity("101", "wlan0", ip);
        String recovered = GameplayPathPolicy.identity("102", "wlan0", ip);
        check("unrelated IPv6 address change retains the locked source",
            GameplayPathPolicy.matchesLockedPath(ip, "wlan0", "wlan0", List.of("fe80::2", ip, "2001:db8::99")));
        check("address order does not change path eligibility",
            GameplayPathPolicy.matchesLockedPath(ip, "wlan0", "wlan0", List.of(ip, "fe80::2")));
        check("same Network with changed IPv4 is not the bound source",
            !GameplayPathPolicy.matchesLockedPath(ip, "wlan0", "wlan0", List.of("192.168.43.3")));
        check("same IPv4 on another interface cannot steal the bind",
            !GameplayPathPolicy.matchesLockedPath(ip, "wlan0", "ap0", List.of(ip)));
        check("missing link properties cannot authorize a bind",
            !GameplayPathPolicy.matchesLockedPath(ip, "wlan0", null, List.of()));
        check("actual Network replacement has a distinct identity", !first.equals(recovered));
        GameplayPathPolicy policy = new GameplayPathPolicy(5000);
        policy.rememberBound(first);
        check("verified startup path needs no second rebind", !policy.needsRebind(first));
        check("repeated healthy refreshes do not disconnect", !policy.needsRebind(first));
        check("missing Network does not authorize a rebind", !policy.needsRebind(null));
        policy.markLost();
        check("lost path is no longer treated as healthy", policy.needsRebind(first));
        check("a recovered exact path can bind immediately", policy.retryDelayMs(recovered, 1000) == 0);
        policy.recordAttempt(recovered, 1000, false);
        check("failed bind is not reported as current", policy.needsRebind(recovered));
        check("duplicate recovery callbacks respect retry limit", policy.retryDelayMs(recovered, 1200) == 4800);
        check("retry delay expires at elapsed-time deadline", policy.retryDelayMs(recovered, 6000) == 0);
        check("another new Network is not blocked by a failed old one",
            policy.retryDelayMs(GameplayPathPolicy.identity("103", "wlan0", ip), 1200) == 0);
        policy.recordAttempt(recovered, 6000, true);
        check("successful bind suppresses repeated reconnect", !policy.needsRebind(recovered));
        policy.markLost();
        policy.rememberBound(first);
        check("new monitor session clears old failed retry", policy.retryDelayMs(recovered, 6100) == 0);
        System.out.println("SUMMARY checks=" + checks + " failures=0");
    }
}
