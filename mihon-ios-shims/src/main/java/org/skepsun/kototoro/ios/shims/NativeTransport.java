package org.skepsun.kototoro.ios.shims;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.ref.Cleaner;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.connection.RealCall;
import okhttp3.internal.http.RealInterceptorChain;
import okio.Buffer;
import okio.Okio;
import okio.Source;
import okio.Timeout;

/**
 * OkHttp's network stage on URLSession: the interpreter-only JVM spends about a second per MiB on TLS record
 * decryption alone, which URLSession does natively. Redirects, cookies, caching and every application/network
 * interceptor stay in OkHttp; only connecting and exchanging bytes moves. Calls that depend on OkHttp's own TLS or
 * socket configuration keep the original path ({@link #handles}).
 */
public final class NativeTransport implements Interceptor {
    private static final int WAIT_MILLIS = 200;
    private static final boolean AVAILABLE = available();
    /** -Dkototoro.native.net.trace=true: one stderr line per request (transport, host, status, time). */
    private static final boolean TRACE = Boolean.getBoolean("kototoro.native.net.trace");
    private static final NativeTransport TERMINAL = new NativeTransport();
    /** Requests sent through URLSession in this process (diagnostics and tests). */
    public static final java.util.concurrent.atomic.AtomicLong REQUESTS = new java.util.concurrent.atomic.AtomicLong();

    private NativeTransport() {}

    private static boolean available() {
        if ("false".equals(System.getProperty("kototoro.native.net"))) return false;
        try {
            return IosNative.netAvailable();
        } catch (UnsatisfiedLinkError notRegistered) {
            return false;
        }
    }

    /**
     * Whether URLSession can carry this call without changing its meaning: plain HTTP(S) on the default proxy, TLS
     * trust, hostname verification and no certificate pins (URLSession applies the system's own).
     */
    public static boolean handles(RealInterceptorChain chain) {
        String keep = keepReason(chain);
        if (TRACE && keep != null) {
            System.err.println("[native-net] okhttp " + keep + " " + chain.getRequest$okhttp().url().host());
        }
        return keep == null;
    }

    /** Why the call keeps OkHttp's own connection, or null when URLSession can carry it. */
    private static String keepReason(RealInterceptorChain chain) {
        if (!AVAILABLE) return "unavailable";
        if (chain.getCall$okhttp().getForWebSocket()) return "websocket";
        Request request = chain.getRequest$okhttp();
        if (!request.url().isHttps() && !"http".equals(request.url().scheme())) return "scheme";
        RequestBody body = request.body();
        if (body != null && (body.isDuplex() || body.isOneShot())) return "streaming-body";
        Proxy proxy = chain.getProxy();
        if (proxy != null && proxy.type() != Proxy.Type.DIRECT) return "proxy";
        if (!chain.getCertificatePinner().getPins().isEmpty()) return "certificate-pins";
        String verifier = chain.getHostnameVerifier().getClass().getName();
        if (!verifier.equals("okhttp3.internal.tls.OkHostnameVerifier")) return "hostname-verifier:" + verifier;
        Object trust = chain.getX509TrustManagerOrNull();
        if (trust != null && !trust.getClass().getName().equals("sun.security.ssl.X509TrustManagerImpl")) {
            return "trust-manager:" + trust.getClass().getName();
        }
        return null;
    }

    /** Runs the client's network interceptors, then this transport, in place of OkHttp's connection. */
    public static Response proceed(RealInterceptorChain chain) throws IOException {
        RealCall call = chain.getCall$okhttp();
        List<Interceptor> interceptors = new ArrayList<>(call.getClient().networkInterceptors());
        interceptors.add(TERMINAL);
        RealInterceptorChain network = new RealInterceptorChain(call, interceptors, 0, null, chain.getRequest$okhttp(),
                chain.getConnectTimeoutMillis$okhttp(), chain.getReadTimeoutMillis$okhttp(),
                chain.getWriteTimeoutMillis$okhttp(), chain.getAuthenticator(), chain.getCache(),
                chain.getCertificatePinner(), chain.getConnectionPool(), chain.getCookieJar(), chain.getDns(),
                chain.getHostnameVerifier(), chain.getProxy(), chain.getProxyAuthenticator(), chain.getProxySelector(),
                chain.getRetryOnConnectionFailure(), chain.getSocketFactory(), chain.getSslSocketFactoryOrNull(),
                chain.getX509TrustManagerOrNull(), chain.getCertificateChainCleaner());
        return network.proceed(chain.getRequest$okhttp());
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        byte[] body = null;
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            body = buffer.readByteArray();
        }
        Headers headers = request.headers();
        List<String> fields = new ArrayList<>(headers.size() * 2 + 2);
        for (int i = 0; i < headers.size(); i++) {
            fields.add(headers.name(i));
            fields.add(headers.value(i));
        }
        MediaType contentType = request.body() == null ? null : request.body().contentType();
        if (contentType != null && request.header("Content-Type") == null) {
            fields.add("Content-Type");
            fields.add(contentType.toString());
        }
        // URLSession's timeout is the idle interval between bytes, like OkHttp's read timeout.
        int timeout = Math.max(chain.connectTimeoutMillis(), chain.readTimeoutMillis());
        long sent = System.currentTimeMillis();
        REQUESTS.incrementAndGet();
        long handle = IosNative.netStart(request.url().toString(), request.method(), fields.toArray(new String[0]), body,
                timeout);
        boolean published = false;
        try {
            String[] head;
            while ((head = IosNative.netHead(handle, WAIT_MILLIS)) == null) checkCancelled(chain.call());
            Headers.Builder received = new Headers.Builder();
            for (int i = 2; i + 1 < head.length; i += 2) received.addUnsafeNonAscii(head[i], head[i + 1]);
            Headers responseHeaders = received.build();
            String length = responseHeaders.get("Content-Length");
            String type = responseHeaders.get("Content-Type");
            ResponseBody responseBody = ResponseBody.create(
                    Okio.buffer(new BodySource(handle, chain.call())),
                    type == null ? null : MediaType.parse(type),
                    length == null ? -1L : parseLength(length));
            Response response = new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(Integer.parseInt(head[0]))
                    .message(head[1])
                    .headers(responseHeaders)
                    .body(responseBody)
                    .sentRequestAtMillis(sent)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();
            published = true;
            if (TRACE) {
                System.err.println("[native-net] urlsession " + request.method() + " " + request.url().host() + " "
                        + head[0] + " " + (System.currentTimeMillis() - sent) + "ms");
            }
            return response;
        } finally {
            if (!published) IosNative.netClose(handle);
        }
    }

    private static long parseLength(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    /** OkHttp cancels a call (including its call timeout) through {@link Call#cancel}; the native wait is polled. */
    private static void checkCancelled(Call call) throws IOException {
        if (call.isCanceled()) throw new IOException("Canceled");
        if (Thread.interrupted()) throw new InterruptedIOException("interrupted");
    }

    /** Extensions often drop responses unclosed (OkHttp only logs a leaked connection); free their native buffer. */
    private static final Cleaner CLEANER = Cleaner.create();

    private static final class BodySource implements Source {
        private final long handle;
        private final Call call;
        private final Cleaner.Cleanable release;
        private final byte[] chunk = new byte[64 * 1024];
        /** Bytes copied from native but not yet requested by the reader. */
        private final Buffer pending = new Buffer();
        private boolean closed;
        private boolean finished;

        BodySource(long handle, Call call) {
            this.handle = handle;
            this.call = call;
            this.release = CLEANER.register(this, () -> IosNative.netClose(handle));
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            if (closed) throw new IllegalStateException("closed");
            if (byteCount == 0) return 0;
            while (pending.size() == 0) {
                if (finished) return -1;
                int count = IosNative.netRead(handle, chunk, WAIT_MILLIS);
                if (count == -1) {
                    finished = true;
                    release.clean(); // complete: the native side holds nothing more for this response
                    return -1;
                }
                if (count > 0) pending.write(chunk, 0, count);
                else checkCancelled(call);
            }
            return pending.read(sink, byteCount);
        }

        @Override
        public Timeout timeout() {
            return Timeout.NONE;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            pending.clear();
            release.clean();
        }
    }
}
