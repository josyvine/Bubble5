package com.txtify.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
// --- THIS IS THE MISSING LINE ---
import java.io.IOException; 
// --------------------------------
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashHandler implements UncaughtExceptionHandler {

    private final UncaughtExceptionHandler defaultUEH;
    private final Context appContext;
    private static final String TAG = "CrashHandler";

    public CrashHandler(Context context) {
        this.appContext = context.getApplicationContext();
        this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        e.printStackTrace(printWriter);
        String stacktrace = result.toString();
        printWriter.close();

        String report = buildReport(stacktrace);

        saveReportToFile(report);

        defaultUEH.uncaughtException(t, e);
    }

    private String buildReport(String stackTrace) {
        StringBuilder report = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        report.append("--- CRASH REPORT ---\n");
        report.append("Timestamp: ").append(sdf.format(new Date())).append("\n");

        report.append("--- DEVICE INFO ---\n");
        report.append("Brand: ").append(Build.BRAND).append("\n");
        report.append("Device: ").append(Build.DEVICE).append("\n");
        report.append("Model: ").append(Build.MODEL).append("\n");
        report.append("Android Version: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        report.append("--- ERROR DETAILS ---\n");
        report.append(stackTrace);

        return report.toString();
    }

    private void saveReportToFile(String report) {
        FileOutputStream fos = null;
        try {
            File crashDir = new File(appContext.getExternalFilesDir(null), "crash_reports");
            if (!crashDir.exists()) {
                crashDir.mkdirs();
            }

            SimpleDateFormat sdfFile = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            String filename = "crash_report_" + sdfFile.format(new Date()) + ".txt";
            File reportFile = new File(crashDir, filename);

            fos = new FileOutputStream(reportFile);
            fos.write(report.getBytes());

            Log.d(TAG, "Crash report saved to: " + reportFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Failed to save crash report.", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) { // This line now works because of the import
                    // ignore
                }
            }
        }
    }
}

