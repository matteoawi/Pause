package it.matteo.pausa.probe;

import android.app.*;
import android.os.Bundle;
import android.net.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Runs from a separate app UID so it follows Pausa's VPN routing. */
public final class ProbeRunner extends Instrumentation {
    private Network vpn;
    private String expandedDomain;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); expandedDomain = arguments == null ? null : arguments.getString("expanded-domain"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); StringBuilder log = new StringBuilder(); int passed = 0;
        try {
            // Xiaomi applies instrumentation foreground network rules asynchronously.
            android.os.SystemClock.sleep(1200);
            ConnectivityManager connectivity = getTargetContext().getSystemService(ConnectivityManager.class);
            for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = network;
            }
            if (vpn == null) throw new IOException("No visible active VPN");
            for (boolean ipv6 : new boolean[]{false, true}) {
                for (boolean tcp : new boolean[]{false, true}) {
                    check("doubleclick.net", ipv6, tcp, 3, log); passed++;
                    check("example.com", ipv6, tcp, 0, log); passed++;
                }
            }
            // Android's ordinary resolver must use the VPN DNS, not just direct test sockets.
            try { InetAddress.getAllByName("pagead2.googlesyndication.com"); throw new IOException("System resolver bypassed filtering"); }
            catch (UnknownHostException expected) { log.append("PASS system DNS blocks ad domain\n"); passed++; }
            if (InetAddress.getAllByName("www.example.com").length == 0) throw new IOException("System resolver returned no address");
            log.append("PASS system DNS resolves allowed domain\n"); passed++;
            if (expandedDomain != null) {
                check(expandedDomain, false, false, 3, log); passed++;
                check("www.youtube.com", false, true, 0, log); passed++;
                check("www.instagram.com", false, false, 0, log); passed++;
            }
            result.putString("stream", "\n" + log + "PASSED " + passed + "/" + (expandedDomain == null ? 10 : 13) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception e) {
            result.putString("stream", "\n" + log + "FAILED after " + passed + " checks: " + e + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void check(String domain, boolean v6, boolean tcp, int expected, StringBuilder log) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String label : domain.split("\\.")) { bytes.write(label.length()); bytes.write(label.getBytes("US-ASCII")); }
        bytes.write(new byte[]{0, 0, 1, 0, 1});
        byte[] query = bytes.toByteArray(), answer;
        String address = v6 ? "fd00:7061:7573::2" : "198.18.0.2";
        if (tcp) {
            try (Socket socket = new Socket()) {
                vpn.bindSocket(socket);
                socket.connect(new InetSocketAddress(address, 53), 5000); socket.setSoTimeout(15000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                // Split the two-byte DNS frame prefix across writes to test reassembly.
                out.writeByte(query.length >>> 8); out.flush(); Thread.sleep(20);
                out.writeByte(query.length); out.write(query); out.flush();
                DataInputStream in = new DataInputStream(socket.getInputStream()); int length = in.readUnsignedShort();
                answer = new byte[length]; in.readFully(answer);
            }
        } else {
            try (DatagramSocket socket = new DatagramSocket()) {
                vpn.bindSocket(socket);
                socket.connect(InetAddress.getByName(address), 53); socket.setSoTimeout(15000);
                socket.send(new DatagramPacket(query, query.length)); byte[] buffer = new byte[65507];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                answer = Arrays.copyOf(buffer, packet.getLength());
            }
        }
        if (answer.length < 12 || answer[0] != 0x12 || answer[1] != 0x34 || (answer[3] & 15) != expected)
            throw new IOException("Invalid response for " + domain + " (status=" + (answer.length > 3 ? answer[3] & 15 : -1) + ")");
        log.append("PASS ").append(v6 ? "IPv6 " : "IPv4 ").append(tcp ? "TCP " : "UDP ").append(domain).append(" rcode=").append(expected).append('\n');
    }
}
