package com.hnedk.dfroot;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalActivity extends Activity {

    private static final String TAG = "DFRoot-Terminal";
    private static final int DAEMON_PORT = 1337;

    private ScrollView termScroll;
    private TextView termOutput;
    private TextView tvStatusBadge;
    private EditText termInput;
    private Button btnSend;
    private Button btnClear;
    private Button btnReconnect;

    private Socket mSocket;
    private OutputStream mOut;
    private Process mLocalProcess;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private volatile boolean mIsRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("Root Terminal");
            getActionBar().setSubtitle("FragSim UID 0 Shell");
        }

        termScroll    = findViewById(R.id.termScroll);
        termOutput    = findViewById(R.id.termOutput);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        termInput     = findViewById(R.id.termInput);
        btnSend       = findViewById(R.id.btnSend);
        btnClear      = findViewById(R.id.btnClear);
        btnReconnect  = findViewById(R.id.btnReconnect);

        btnSend.setOnClickListener(v -> submitInput());
        termInput.setOnEditorActionListener((v, actionId, event) -> {
            submitInput();
            return true;
        });

        btnClear.setOnClickListener(v -> termOutput.setText(""));
        btnReconnect.setOnClickListener(v -> connectSession());

        setupQuickChips();

        connectSession();
    }

    private void setupQuickChips() {
        bindChip(R.id.chipId, "id");
        bindChip(R.id.chipWhoami, "whoami");
        bindChip(R.id.chipUname, "uname -a");
        bindChip(R.id.chipShizuku, "/data/local/tmp/start_shizuku || /data/user_de/0/com.hnedk.dfroot/bootstrap --shizuku");
        bindChip(R.id.chipLs, "ls -la /data");
        bindChip(R.id.chipDmesg, "dmesg | tail -n 25");
        bindChip(R.id.chipPs, "ps -ef | grep -E 'ksu|su|root'");
    }

    private void bindChip(int viewId, String cmd) {
        Button chip = findViewById(viewId);
        if (chip != null) {
            chip.setOnClickListener(v -> sendCommand(cmd));
        }
    }

    private void appendOutput(String text) {
        mMain.post(() -> {
            if (termOutput != null) {
                termOutput.append(text);
            }
            if (termScroll != null) {
                termScroll.post(() -> termScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void setStatus(String statusText, int color) {
        mMain.post(() -> {
            if (tvStatusBadge != null) {
                tvStatusBadge.setText(statusText);
                tvStatusBadge.setTextColor(color);
            }
        });
    }

    private synchronized void disconnect() {
        mIsRunning = false;
        try {
            if (mOut != null) mOut.close();
        } catch (Exception ignored) {}
        mOut = null;

        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
        mSocket = null;

        if (mLocalProcess != null) {
            try {
                mLocalProcess.destroy();
            } catch (Exception ignored) {}
            mLocalProcess = null;
        }
    }

    private void connectSession() {
        disconnect();
        setStatus("● Connecting...", 0xFFFFB74D);
        appendOutput("\n[Connecting to root daemon 127.0.0.1:" + DAEMON_PORT + "...]\n");

        mExecutor.execute(() -> {
            boolean connected = false;
            // Coba connect ke socket daemon hingga 3 kali
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", DAEMON_PORT), 1500);
                    mSocket = socket;
                    mOut = socket.getOutputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    mIsRunning = true;
                    connected = true;

                    setStatus("● Connected (UID 0)", 0xFF81C784);
                    appendOutput("[Root Shell Connected - UID 0 Ready]\n");

                    char[] buf = new char[1024];
                    int n;
                    while (mIsRunning && (n = reader.read(buf)) != -1) {
                        appendOutput(new String(buf, 0, n));
                    }
                    break;
                } catch (Exception e) {
                    if (attempt < 3) {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                }
            }

            if (!connected) {
                // Fallback: spawn local process via su wrapper
                trySpawnFallbackProcess();
            } else {
                setStatus("● Disconnected", 0xFFE57373);
                appendOutput("\n[Connection closed]\n");
            }
        });
    }

    private void trySpawnFallbackProcess() {
        appendOutput("[Daemon socket busy/offline, trying local su wrapper...]\n");
        try {
            String suPath = new java.io.File("/data/local/tmp/su").exists() ? "/data/local/tmp/su" : "su";
            ProcessBuilder pb = new ProcessBuilder(suPath);
            pb.redirectErrorStream(true);
            mLocalProcess = pb.start();
            mOut = mLocalProcess.getOutputStream();
            mIsRunning = true;

            setStatus("● Connected (su fallback)", 0xFF81C784);
            appendOutput("[Fallback su shell opened]\n");

            BufferedReader reader = new BufferedReader(new InputStreamReader(mLocalProcess.getInputStream()));
            char[] buf = new char[1024];
            int n;
            while (mIsRunning && (n = reader.read(buf)) != -1) {
                appendOutput(new String(buf, 0, n));
            }
        } catch (Exception e) {
            setStatus("● Error", 0xFFE57373);
            appendOutput("[Gagal terhubung: " + e.getMessage() + "]\n");
            appendOutput("[Pastikan Root exploit sudah dijalankan terlebih dahulu]\n");
        } finally {
            setStatus("● Disconnected", 0xFFE57373);
        }
    }

    private void submitInput() {
        if (termInput == null) return;
        String cmd = termInput.getText().toString().trim();
        if (cmd.isEmpty()) return;
        termInput.setText("");
        sendCommand(cmd);
    }

    private void sendCommand(String cmd) {
        if (cmd.equalsIgnoreCase("clear")) {
            termOutput.setText("");
            return;
        }

        appendOutput("\n# " + cmd + "\n");
        mExecutor.execute(() -> {
            try {
                if (mOut != null) {
                    mOut.write((cmd + "\n").getBytes());
                    mOut.flush();
                } else {
                    mMain.post(() -> Toast.makeText(this, "Terminal belum terhubung", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                appendOutput("[Send error: " + e.getMessage() + "]\n");
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
