package org.skepsun.kototoro.ios.shims;

/**
 * Apple framework operations, registered by KototoroNative.mm before any extension is constructed.
 */
public final class IosNative {
    static {
        String library = System.getProperty("kototoro.native.library");
        if (library != null) System.load(library);
    }

    private IosNative() {}

    public static native int[] decodeImage(byte[] data, boolean boundsOnly, int sampleSize);

    public static native byte[] encodeImage(
            int[] pixels, int width, int height, String format, int quality);

    public static native float[] measureText(String text, String family, int style, float size);

    public static native void drawText(
            int[] pixels,
            int width,
            int height,
            String text,
            float x,
            float y,
            String family,
            int style,
            float size,
            int color,
            float[] transform);

    public static native long openPdf(byte[] data);

    public static native int[] pdfPageInfo(long document, int index);

    public static native void renderPdf(
            long document, int index, int[] pixels, int width, int height);

    public static native void closePdf(long document);

    public static native long createJavaScript(Object bridge);

    public static native String evaluateJavaScript(long context, String script, String filename);

    public static native void closeJavaScript(long context);

    /** Whether the URLSession transport is registered (an unregistered native throws UnsatisfiedLinkError). */
    public static native boolean netAvailable();

    /** Starts a request; [headers] alternate names and values. Returns a handle for the calls below. */
    public static native long netStart(String url, String method, String[] headers, byte[] body, int timeoutMillis)
            throws java.io.IOException;

    /** [code, message, name, value, ...], or null while the head has not arrived within [waitMillis]. */
    public static native String[] netHead(long request, int waitMillis) throws java.io.IOException;

    /** Body bytes copied into [buffer]: their count, -1 at the end, or 0 when none arrived within [waitMillis]. */
    public static native int netRead(long request, byte[] buffer, int waitMillis) throws java.io.IOException;

    /** Releases the request and cancels it if still running; idempotent. */
    public static native void netClose(long request);

    /** A CommonCrypto digest context for {@link IosDigestProvider}'s algorithm index. */
    public static native long digestCreate(int algorithm);

    public static native void digestUpdate(long digest, byte[] data, int offset, int length);

    /** The digest of the input since the last reset; the context then starts over. */
    public static native byte[] digestFinish(long digest);

    public static native void digestReset(long digest);

    /** An independent context continuing from the same state (MessageDigest.clone). */
    public static native long digestCopy(long digest);

    /** Frees the context; called exactly once per context. */
    public static native void digestFree(long digest);
}
