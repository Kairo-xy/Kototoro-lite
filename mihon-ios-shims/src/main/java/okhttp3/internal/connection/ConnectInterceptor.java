package okhttp3.internal.connection;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.internal.http.RealInterceptorChain;
import org.skepsun.kototoro.ios.shims.NativeTransport;

/**
 * Takes the place of OkHttp 5.4.0's ConnectInterceptor on the iOS class path (this jar goes first): calls that
 * {@link NativeTransport} can carry go to URLSession after the application interceptors, redirects, cookie bridge
 * and cache have run; the rest open an OkHttp connection exactly as the original does. Pinned to OkHttp 5.4.0's
 * internal ABI (initExchange / copy), like the other SDK adapters; re-check it when OkHttp changes.
 */
public final class ConnectInterceptor implements Interceptor {
    public static final ConnectInterceptor INSTANCE = new ConnectInterceptor();

    /** RealInterceptorChain keeps its position private; the original copy keeps it unchanged. */
    private static final java.lang.reflect.Field INDEX;

    static {
        try {
            INDEX = RealInterceptorChain.class.getDeclaredField("index");
            INDEX.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ConnectInterceptor() {}

    private static int index(RealInterceptorChain chain) {
        try {
            return INDEX.getInt(chain);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Response intercept(Interceptor.Chain chain) throws IOException {
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        if (NativeTransport.handles(realChain)) return NativeTransport.proceed(realChain);
        Exchange exchange = realChain.getCall$okhttp().initExchange$okhttp(realChain);
        // Same as the original: copy the chain with only the exchange replaced.
        RealInterceptorChain connected = realChain.copy$okhttp(index(realChain), exchange,
                realChain.getRequest$okhttp(), realChain.getConnectTimeoutMillis$okhttp(),
                realChain.getReadTimeoutMillis$okhttp(), realChain.getWriteTimeoutMillis$okhttp(),
                realChain.getAuthenticator(), realChain.getCache(), realChain.getCertificatePinner(),
                realChain.getConnectionPool(), realChain.getCookieJar(), realChain.getDns(),
                realChain.getHostnameVerifier(), realChain.getProxy(), realChain.getProxyAuthenticator(),
                realChain.getProxySelector(), realChain.getRetryOnConnectionFailure(), realChain.getSocketFactory(),
                realChain.getSslSocketFactoryOrNull(), realChain.getX509TrustManagerOrNull(),
                realChain.getCertificateChainCleaner());
        return connected.proceed(realChain.getRequest$okhttp());
    }
}
