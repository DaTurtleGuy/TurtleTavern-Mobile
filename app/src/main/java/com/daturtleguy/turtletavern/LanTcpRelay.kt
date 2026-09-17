package com.daturtleguy.turtletavern

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens on all interfaces (0.0.0.0) and forwards every TCP connection to the
 * Go server bound on 127.0.0.1. Byte-level relay: no HTTP parsing, so Host
 * headers, cookies and CSRF tokens pass through untouched.
 *
 * Why a relay instead of binding Go to 0.0.0.0: the Go server keeps its
 * loopback-only socket, so its IP whitelist still sees RemoteAddr 127.0.0.1
 * (this class dials locally) and no Go/AAR rebuild is needed.
 */
class LanTcpRelay {

    private val lock = Any()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val openConnections = HashSet<Socket>()

    /** Actual LAN port bound (may differ from requested on conflict fallback). */
    @Volatile
    var boundPort: Int = 0
        private set

    fun isRunning(): Boolean = running.get()

    /**
     * Binds 0.0.0.0:[lanPort] and forwards to 127.0.0.1:[targetPort].
     * Pass lanPort=0 to let the OS pick. On conflict, falls back to an
     * OS-picked port rather than failing. Returns the bound LAN port.
     */
    fun start(lanPort: Int, targetPort: Int): Int {
        synchronized(lock) {
            stopLocked()
            require(targetPort in 1..65535) { "no local server port yet" }
            val srv = ServerSocket()
            srv.reuseAddress = true
            try {
                srv.bind(InetSocketAddress("0.0.0.0", lanPort))
            } catch (t: Throwable) {
                if (lanPort == 0) throw t
                // Preferred LAN port taken (e.g. stale socket): take any free one.
                srv.bind(InetSocketAddress("0.0.0.0", 0))
            }
            server = srv
            boundPort = srv.localPort
            running.set(true)
            val thread = Thread({ acceptLoop(srv, targetPort) }, "lan-relay-accept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()
            AppLog.i(TAG, "LAN relay listening on 0.0.0.0:$boundPort -> 127.0.0.1:$targetPort")
            return boundPort
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val wasRunning = running.getAndSet(false)
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        boundPort = 0
        val conns = ArrayList(openConnections)
        openConnections.clear()
        for (conn in conns) {
            try {
                conn.close()
            } catch (_: Throwable) {
            }
        }
        val thread = acceptThread
        acceptThread = null
        if (thread != null && Thread.currentThread() !== thread) {
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (wasRunning) {
            AppLog.i(TAG, "LAN relay stopped")
        }
    }

    private fun acceptLoop(srv: ServerSocket, targetPort: Int) {
        while (running.get()) {
            val client: Socket = try {
                srv.accept()
            } catch (_: Throwable) {
                break // server socket closed -> shutdown
            }
            val worker = Thread({ relayConnection(client, targetPort) }, "lan-relay-conn")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun relayConnection(client: Socket, targetPort: Int) {
        val upstream: Socket = try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            s
        } catch (t: Throwable) {
            AppLog.w(TAG, "LAN relay: local server unreachable: " + t.message)
            try {
                client.close()
            } catch (_: Throwable) {
            }
            return
        }
        client.tcpNoDelay = true
        synchronized(lock) {
            if (!running.get()) {
                try {
                    client.close()
                } catch (_: Throwable) {
                }
                try {
                    upstream.close()
                } catch (_: Throwable) {
                }
                return
            }
            openConnections.add(client)
            openConnections.add(upstream)
        }
        val fwd = Thread({ copyQuiet(client, upstream, true) }, "lan-relay-fwd")
        val rev = Thread({ copyQuiet(upstream, client, false) }, "lan-relay-rev")
        fwd.isDaemon = true
        rev.isDaemon = true
        fwd.start()
        rev.start()
        try {
            // Either direction finishing ends the connection: the other copy
            // unblocks when we close the sockets below.
            fwd.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) {
                openConnections.remove(client)
                openConnections.remove(upstream)
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
            try {
                upstream.close()
            } catch (_: Throwable) {
            }
        }
        try {
            rev.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun copyQuiet(from: Socket, to: Socket, fromIsClient: Boolean) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
            // Half-close the write side so the peer sees EOF promptly.
            try {
                if (!to.isClosed) to.shutdownOutput()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
            if (fromIsClient) {
                AppLog.i(TAG, "LAN relay: connection closed")
            }
        }
    }

    companion object {
        private const val TAG = "TurtleTavern"

        /** Non-loopback IPv4 addresses of this device (Wi-Fi/LAN), for the drawer URL line. */
        fun lanIPv4Addresses(): List<String> {
            val out = ArrayList<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
                for (iface in ifaces) {
                    try {
                        if (!iface.isUp || iface.isLoopback) continue
                    } catch (_: Throwable) {
                        continue
                    }
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            out.add(addr.hostAddress ?: continue)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            return out.distinct().sorted()
        }
    }
}
