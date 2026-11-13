package com.beeshtv.home;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Runs one shell command on this TV through its own network debugging (adb on port 5555), the way a
 * computer would. Since Android 14 an app can't stop another app's background processes itself, but
 * the shell can: Clear all in Recents uses this for `am kill`, which only stops apps that aren't on
 * screen or playing. The first time, the TV asks to allow BeeshTV's key, as it does for a computer;
 * the key stays in BeeshTV's private storage. Nothing here leaves the TV.
 *
 * The adb protocol, version 1, without TLS (what `adb connect` uses for network debugging).
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3;
    /** SHA-1 DigestInfo prefix: adbd checks a PKCS#1 signature of the token as if it were a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    /** Why the last run failed, for a message; null after success. */
    static volatile String error;

    /**
     * Runs the command and returns its output, or null when the TV's network debugging is off, the
     * TV said no, or nobody answered the TV's question within a minute. Blocks: call off the main thread.
     */
    static String shell(Context c, String command) {
        error = null;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            s.setSoTimeout(60_000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            KeyPair keys = keys(c);
            send(out, CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Msg m = read(in);
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.arg0 != TOKEN) { error = "unexpected reply"; return null; }
                if (!signed) {
                    send(out, AUTH, SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                    signed = true;
                } else {
                    // Not known yet: the TV asks "Allow debugging?" and answers once someone chooses.
                    send(out, AUTH, PUBLIC_KEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                }
            }
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    text.append(new String(m.data, StandardCharsets.UTF_8));
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    send(out, CLSE, 1, m.arg0, new byte[0]);
                    return text.toString();
                }
            }
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            error = "network debugging is off";
            return null;
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
            return null;
        }
    }

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad length");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        rsa.update(SHA1_PREFIX);
        rsa.update(token);
        return rsa.doFinal();
    }

    /** The public key the way adbd stores it: Android's RSAPublicKey struct, base64, then a name. */
    private static byte[] publicKey(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        b.put(littleEndian(n, 256)).put(littleEndian(rr, 256));
        b.putInt(key.getPublicExponent().intValue());
        String line = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " BeeshTV@tv\0";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] littleEndian(BigInteger v, int size) {
        byte[] big = v.toByteArray(), out = new byte[size];
        for (int i = 0; i < size && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }

    /** BeeshTV's own adb key, made once and kept in its private files. */
    private static synchronized KeyPair keys(Context c) throws Exception {
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(priv)) { o.write(k.getPrivate().getEncoded()); }
        try (FileOutputStream o = new FileOutputStream(pub)) { o.write(k.getPublic().getEncoded()); }
        return k;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            new DataInputStream(in).readFully(b);
            return b;
        }
    }
}
