package app.bside.music;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Spotify sign-in sends you back to http://127.0.0.1:43821/callback, a loopback address, which is what Spotify
 * allows for apps. This tiny server runs only while a sign-in is waiting (15 minutes at most). It answers that
 * one request with a "back to B-Side" page and hands its query string (code and state) to the player.
 */
final class LoopbackLogin {
    interface Listener {
        void onCallback(String query);
    }

    static final int PORT = 43821;
    private static ServerSocket server;

    /** Starts listening, or keeps the sign-in that is already waiting. False if the port can't be opened. */
    static synchronized boolean start(final Listener listener) {
        if (server != null && !server.isClosed()) return true;
        final ServerSocket ss;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), PORT), 4);
            ss.setSoTimeout(15 * 60 * 1000);
        } catch (IOException e) {
            return false;
        }
        server = ss;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Socket s = ss.accept();
                        String query = null;
                        try {
                            query = answer(s);
                        } catch (IOException ignored) {
                        } finally {
                            close(s);
                        }
                        if (query != null) {
                            listener.onCallback(query);
                            break;
                        }
                    }
                } catch (IOException ignored) {
                    // timed out, or the socket was closed
                } finally {
                    close(ss);
                    synchronized (LoopbackLogin.class) {
                        if (server == ss) server = null;
                    }
                }
            }
        }, "spotify-login");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Replies to one request. Returns the query string for /callback, or null for anything else. */
    private static String answer(Socket s) throws IOException {
        s.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine(), target = "";
        if (line != null) {
            String[] p = line.split(" ");
            if (p.length >= 2 && "GET".equals(p[0])) target = p[1];
        }
        for (String h = in.readLine(); h != null && !h.isEmpty(); h = in.readLine()) {
            // read the headers so the browser gets the whole reply
        }
        boolean ok = target.equals("/callback") || target.startsWith("/callback?");
        int q = target.indexOf('?');
        String query = ok ? (q >= 0 ? target.substring(q + 1) : "") : null;
        byte[] body = (ok ? page(query.contains("error=")) : "Not found").getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        return query;
    }

    private static String page(boolean failed) {
        String back = "intent://spotify#Intent;scheme=bside;package=app.bside.music;end";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>B-Side</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px;box-sizing:border-box;"
                + "font:16px/1.5 system-ui,sans-serif;text-align:center;background:#f3f3f6;color:#121216}h1{font-size:22px;margin:0 0 8px}"
                + "p{margin:0;color:#686872}a{display:inline-block;margin-top:22px;padding:14px 28px;border-radius:26px;background:#2f6bff;"
                + "color:#fff;font-weight:600;text-decoration:none}@media(prefers-color-scheme:dark){body{background:#030304;color:#f2f2f5}"
                + "p{color:#a0a0aa}}</style></head><body><div><h1>" + (failed ? "Spotify sign-in was cancelled" : "Signed in to Spotify")
                + "</h1><p>" + (failed ? "You can try again from B-Side." : "Go back to B-Side to choose what to import.")
                + "</p><a href=\"" + back + "\">Back to B-Side</a></div><script>setTimeout(function(){location.href=\"" + back
                + "\"},400)</script></body></html>";
    }

    private static void close(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private LoopbackLogin() {
    }
}
