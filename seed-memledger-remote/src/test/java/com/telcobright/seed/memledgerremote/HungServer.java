package com.telcobright.seed.memledgerremote;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A mem-ledger server that is THERE and never answers — recall-db hung (a stall inside it), not stopped: every connection is accepted
 * and kept, nothing is read, nothing is written. A client's call waits out its whole deadline ({@code waiting_for_connection}), as the
 * G2 run saw behind its relay; a STOPPED server would refuse the connection at once instead.
 */
final class HungServer implements AutoCloseable {

    private final ServerSocket socket;
    private final List<Socket> kept = new CopyOnWriteArrayList<>();

    HungServer() {
        try {
            socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("the hung server did not bind", e);
        }
        Thread.ofPlatform().daemon().name("hung-server").start(this::acceptAndKeepSilent);
    }

    String address() { return "127.0.0.1:" + socket.getLocalPort(); }

    private void acceptAndKeepSilent() {
        while (!socket.isClosed()) {
            try {
                kept.add(socket.accept());
            } catch (IOException closed) {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        for (Socket s : kept) s.close();
    }
}
