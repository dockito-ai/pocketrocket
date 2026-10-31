import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The spike's hidden virtual display (docs/android-tools-spike.md). Runs as adb's shell user:
 *   CLASSPATH=/data/local/tmp/vdisplay.dex app_process / VDisplay [width height dpi]
 * Prints "display <id>", then reads stdin: "shot <path>" writes the latest frame as a PNG, "quit" removes the display.
 * The display is backed by an ImageReader, so nothing it shows reaches the real screen.
 */
public final class VDisplay {
    // DisplayManager flags. Several are hidden, so they are spelled out.
    private static final int PUBLIC = 1;
    private static final int OWN_CONTENT_ONLY = 1 << 3;
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int ROTATES_WITH_CONTENT = 1 << 7;
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int TOUCH_FEEDBACK_DISABLED = 1 << 13;

    public static void main(String[] args) throws Exception {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 1080;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 2400;
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 420;

        Looper.prepareMainLooper();
        Context context = new ShellContext(systemContext());
        Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        DisplayManager dm = ctor.newInstance(context);

        HandlerThread thread = new HandlerThread("frames");
        thread.start();
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        final AtomicReference<Bitmap> latest = new AtomicReference<>();
        reader.setOnImageAvailableListener(r -> {
            try (Image image = r.acquireLatestImage()) {
                if (image != null) latest.set(toBitmap(image));
            }
        }, new Handler(thread.getLooper()));

        int flags = PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL
                | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED;
        VirtualDisplay display = dm.createVirtualDisplay("pocketrocket-test", w, h, dpi, reader.getSurface(), flags);
        System.out.println("display " + display.getDisplay().getDisplayId());
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.startsWith("shot ")) {
                Bitmap b = latest.get();
                if (b == null) {
                    System.out.println("no frame yet");
                } else {
                    try (FileOutputStream out = new FileOutputStream(line.substring(5))) {
                        b.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    System.out.println("wrote " + line.substring(5));
                }
                System.out.flush();
            }
        }
        display.release();
        System.exit(0);
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int stride = plane.getRowStride() / plane.getPixelStride();
        Bitmap b = Bitmap.createBitmap(stride, image.getHeight(), Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(buffer);
        return b;
    }

    /** A system context for a plain app_process, without running ActivityThread's full start-up. */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"})
    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        Field system = at.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(thread, true);
        return (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
    }

    /** Says it is the shell package, which is what adb's uid owns. */
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
    }
}
