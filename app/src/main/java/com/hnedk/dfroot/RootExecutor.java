package com.hnedk.dfroot;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Executes shell commands with UID 0 root privilege.
 * Prioritizes direct connection to DFRoot's local daemon (127.0.0.1:1337).
 * Falls back to /data/local/tmp/su or system su if daemon socket is unavailable.
 */
public class RootExecutor {

    private static final String TAG = "RootExecutor";
    private static final int DAEMON_PORT = 1337;

    public interface OutputListener {
        void onOutputLine(String line);
    }

    public static class Result {
        public final int exitCode;
        public final String output;

        public Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    /**
     * Executes a command string synchronously as UID 0.
     */
    public static Result execute(String command) {
        return execute(command, null);
    }

    /**
     * Executes a command string synchronously as UID 0 and streams stdout/stderr lines.
     */
    public static Result execute(String command, OutputListener listener) {
        StringBuilder sb = new StringBuilder();

        // 1. Attempt connection via DFRoot root shell daemon socket (port 1337)
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", DAEMON_PORT), 1500);
            OutputStream out = socket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Send command wrapped with exit sentinel to capture completion
            String marker = "__DFROOT_CMD_DONE_" + System.currentTimeMillis() + "__";
            String fullCommand = command + "\necho \"" + marker + ":$?\"\nexit\n";
            out.write(fullCommand.getBytes());
            out.flush();

            String line;
            int exitCode = -1;
            while ((line = reader.readLine()) != null) {
                if (line.contains(marker)) {
                    int idx = line.indexOf(marker);
                    String part = line.substring(idx + marker.length() + 1).trim();
                    try {
                        exitCode = Integer.parseInt(part);
                    } catch (Exception ignored) {
                        exitCode = 0;
                    }
                    break;
                }
                sb.append(line).append("\n");
                if (listener != null) {
                    listener.onOutputLine(line);
                }
            }
            if (exitCode != -1) {
                return new Result(exitCode, sb.toString().trim());
            }
        } catch (Exception ignored) {
            // Fall through to local process fallback
        }

        // 2. Fallback to /data/local/tmp/su or system su binary
        try {
            String suPath = new File("/data/local/tmp/su").exists() ? "/data/local/tmp/su" : "su";
            ProcessBuilder pb = new ProcessBuilder(suPath, "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
                if (listener != null) {
                    listener.onOutputLine(line);
                }
            }
            int rc = process.waitFor();
            return new Result(rc, sb.toString().trim());
        } catch (Exception e) {
            String err = "Root execution failed: " + e.getMessage();
            Log.e(TAG, err, e);
            if (listener != null) listener.onOutputLine(err);
            return new Result(-1, err);
        }
    }
}
