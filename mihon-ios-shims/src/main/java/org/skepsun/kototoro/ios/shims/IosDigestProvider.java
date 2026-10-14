package org.skepsun.kototoro.ios.shims;

import java.lang.ref.Cleaner;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;

/**
 * MD5, SHA-1, SHA-256, SHA-384 and SHA-512 on CommonCrypto, ahead of the JDK's providers. The interpreter-only JVM
 * hashes about 1 MiB per second in Java, which every image artifact, extension check and TLS handshake pays;
 * natively it is milliseconds. Installed by KototoroNative.mm right after the native methods are registered.
 */
public final class IosDigestProvider extends Provider {
    static final String NAME = "KototoroIOS";
    private static final Cleaner CLEANER = Cleaner.create();
    private static final String[][] ALGORITHMS = {
        {"MD5"},
        {"SHA-1", "SHA", "SHA1", "1.3.14.3.2.26", "OID.1.3.14.3.2.26"},
        {"SHA-256", "SHA256", "2.16.840.1.101.3.4.2.1", "OID.2.16.840.1.101.3.4.2.1"},
        {"SHA-384", "SHA384", "2.16.840.1.101.3.4.2.2", "OID.2.16.840.1.101.3.4.2.2"},
        {"SHA-512", "SHA512", "2.16.840.1.101.3.4.2.3", "OID.2.16.840.1.101.3.4.2.3"},
    };
    private static final int[] LENGTHS = {16, 20, 32, 48, 64};

    private IosDigestProvider() {
        super(NAME, "1.0", "Kototoro CommonCrypto message digests");
        for (int index = 0; index < ALGORITHMS.length; index++) {
            String[] names = ALGORITHMS[index];
            String className = IosDigest.class.getName() + "$" + names[0].replace("-", "");
            put("MessageDigest." + names[0], className);
            put("MessageDigest." + names[0] + " ImplementedIn", "Software");
            for (int alias = 1; alias < names.length; alias++) put("Alg.Alias.MessageDigest." + names[alias], names[0]);
        }
    }

    /** Puts the provider first when the native digests are registered; idempotent. */
    public static synchronized void install() {
        if (Security.getProvider(NAME) != null || "false".equals(System.getProperty("kototoro.native.digest"))) return;
        try {
            IosNative.digestFree(IosNative.digestCreate(2));
        } catch (UnsatisfiedLinkError notRegistered) {
            return;
        }
        Security.insertProviderAt(new IosDigestProvider(), 1);
    }

    /** One CommonCrypto context; single bytes are batched so update(byte) does not cross JNI each time. */
    public abstract static class IosDigest extends MessageDigestSpi implements Cloneable {
        private final int algorithm;
        private long context;
        private Cleaner.Cleanable release;
        private byte[] pending = new byte[64];
        private int pendingSize;

        IosDigest(int algorithm) {
            this.algorithm = algorithm;
            own(IosNative.digestCreate(algorithm));
        }

        private void own(long handle) {
            context = handle;
            release = CLEANER.register(this, () -> IosNative.digestFree(handle));
        }

        private void flush() {
            if (pendingSize == 0) return;
            IosNative.digestUpdate(context, pending, 0, pendingSize);
            pendingSize = 0;
        }

        @Override
        protected int engineGetDigestLength() {
            return LENGTHS[algorithm];
        }

        @Override
        protected void engineUpdate(byte input) {
            if (pendingSize == pending.length) flush();
            pending[pendingSize++] = input;
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int length) {
            if (length <= pending.length - pendingSize && length < 32) {
                System.arraycopy(input, offset, pending, pendingSize, length);
                pendingSize += length;
                return;
            }
            flush();
            IosNative.digestUpdate(context, input, offset, length);
        }

        @Override
        protected byte[] engineDigest() {
            flush();
            return IosNative.digestFinish(context);
        }

        @Override
        protected void engineReset() {
            pendingSize = 0;
            IosNative.digestReset(context);
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            IosDigest copy = (IosDigest) super.clone();
            copy.pending = pending.clone();
            copy.own(IosNative.digestCopy(context));
            return copy;
        }

        public static final class MD5 extends IosDigest { public MD5() { super(0); } }
        public static final class SHA1 extends IosDigest { public SHA1() { super(1); } }
        public static final class SHA256 extends IosDigest { public SHA256() { super(2); } }
        public static final class SHA384 extends IosDigest { public SHA384() { super(3); } }
        public static final class SHA512 extends IosDigest { public SHA512() { super(4); } }
    }
}
