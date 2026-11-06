package it.matteo.pausa;

import android.net.VpnService;
import it.matteo.pausa.core.DnsMessage;
import it.matteo.pausa.core.DohResolver;
import java.io.Closeable;
import java.io.IOException;

/** The app UID is excluded from the DNS-only VPN; sockets are also protected. */
public final class DnsUpstream implements Closeable {
    private final DohResolver resolver;
    public DnsUpstream(VpnService service) { resolver = new DohResolver(service::protect); }
    public byte[] resolve(DnsMessage query) throws IOException { return resolver.resolve(query); }
    @Override public void close() { resolver.close(); }
}
