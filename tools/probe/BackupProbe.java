import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.lang.reflect.Method;

/** Tests shell backup authorization, optionally exporting only recorder metadata (feature 3). */
public final class BackupProbe {
    public static void main(String[] args) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "MiuiBackup");
        if (binder == null) throw new IllegalStateException("MiuiBackup unavailable");
        Class<?> stub = Class.forName("miui.app.backup.IBackupManager$Stub");
        Class<?> api = Class.forName("miui.app.backup.IBackupManager");
        Object service = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        System.out.println("uid=" + android.os.Process.myUid());
        System.out.println("state=" + api.getMethod("getState").invoke(service));
        boolean idle = (Boolean) api.getMethod("isServiceIdle").invoke(service);
        System.out.println("idle=" + idle);
        if (!idle) System.exit(2);
        Class<?> observer = Class.forName("miui.app.backup.IBackupServiceStateObserver");
        Method acquire = api.getMethod("acquire", observer, IBinder.class);
        boolean acquired = (Boolean) acquire.invoke(service, null, new Binder());
        System.out.println("shell backup authorization acquired=" + acquired);
        if (acquired) {
            try {
                if (args.length == 1 && "--export-metadata".equals(args[0])) {
                    File file = new File("/data/local/tmp/xiaomi-recorder-metadata.bak");
                    if (file.exists()) throw new IllegalStateException("Output already exists; refusing overwrite");
                    file.createNewFile();
                    android.system.Os.chmod(file.getPath(), 0600);
                    try (ParcelFileDescriptor out = ParcelFileDescriptor.open(file,
                            ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                        Class<?> callback = Class.forName("miui.app.backup.IPackageBackupRestoreObserver");
                        Method backup = api.getMethod("backupPackage", ParcelFileDescriptor.class,
                                ParcelFileDescriptor.class, String.class, int.class, String.class,
                                String.class, boolean.class, boolean.class, boolean.class, boolean.class,
                                callback);
                        System.out.println("exporting recorder metadata only, feature=3, includeApk=false");
                        backup.invoke(service, out, null, "com.android.soundrecorder", 3, null, null,
                                false, false, false, false, null);
                    }
                    System.out.println("backup bytes=" + file.length());
                    System.out.println("state after export=" + api.getMethod("getState").invoke(service));
                }
            } finally {
                api.getMethod("release", observer).invoke(service, new Object[]{null});
                System.out.println("released=true");
            }
        }
        System.exit(acquired ? 0 : 3);
    }
}
