package com.vaklinov.zcashui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.vaklinov.zcashui.OSUtil.OS_TYPE;
import com.vaklinov.zcashui.ZCashClientCaller.WalletCallException;

public class StartupProgressDialog extends JWindow {
    
    private static final Logger LOG = Logger.getLogger(StartupProgressDialog.class.getName());

    private static final int POLL_PERIOD = 250;
    private static final int STARTUP_ERROR_CODE = -28;
    
    private BorderLayout borderLayout1 = new BorderLayout();
    private JLabel imageLabel = new JLabel();
    private JLabel progressLabel = new JLabel();
    private JPanel southPanel = new JPanel();
    private BorderLayout southPanelLayout = new BorderLayout();
    private JProgressBar progressBar = new JProgressBar();
    private ImageIcon imageIcon;
    
    private final ZCashClientCaller clientCaller;
    
    public StartupProgressDialog(ZCashClientCaller clientCaller) {
        this.clientCaller = clientCaller;
        
        URL iconUrl = this.getClass().getClassLoader().getResource("images/"+ZCashUI.NAME+"-246x246.png");
        imageIcon = new ImageIcon(iconUrl);
        imageLabel.setIcon(imageIcon);
        Container contentPane = getContentPane();
        contentPane.setLayout(borderLayout1);
        southPanel.setLayout(southPanelLayout);
        contentPane.add(imageLabel,BorderLayout.CENTER);
        contentPane.add(southPanel, BorderLayout.SOUTH);
        progressBar.setIndeterminate(true);
        southPanel.add(progressBar, BorderLayout.NORTH);
        progressLabel.setText("Starting...");
        southPanel.add(progressLabel, BorderLayout.SOUTH);
        setLocationRelativeTo(null);
        pack();
    }
    
    public void waitForStartup() throws IOException,
        InterruptedException,WalletCallException,InvocationTargetException {
        
        // special handling of OSX app bundle
        if (OSUtil.getOSType() == OS_TYPE.MAC_OS) {
            ProvingKeyFetcher keyFetcher = new ProvingKeyFetcher();
            keyFetcher.fetchIfMissing(this);
            if ("true".equalsIgnoreCase(System.getProperty("launching.from.appbundle")))
                performOSXBundleLaunch();
        }
        
        LOG.info("checking if zcashd is already running...");
        boolean shouldStartZCashd;
        try {
            clientCaller.getInfo();
            shouldStartZCashd = false;
        } catch (Exception e) {
            shouldStartZCashd = true;
        }
        
        if (!shouldStartZCashd) {
            LOG.info("zcashd already running");
            doDispose();
            return;
        }
        
        LOG.info("trying to start zcashd");
        final Process daemonProcess = clientCaller.startDaemon();
        Thread.sleep(POLL_PERIOD); // just a little extra
        while(true) {
            Thread.sleep(POLL_PERIOD);
            JsonObject info = clientCaller.getInfo();
            JsonValue code = info.get("code");
            if (code == null || (code.asInt() != STARTUP_ERROR_CODE))
                break;
            final String message = info.getString("message", "???");
            setProgressText(message);
        }
        LOG.info("zcashd started");
        doDispose();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                LOG.info("Stopping zcashd because we started it");
                try {
                    clientCaller.stopDaemon();
                    daemonProcess.waitFor(1000, TimeUnit.MILLISECONDS);
                    if (daemonProcess.isAlive()) {
                        LOG.info("zcashd is still alive, killing forcefully");
                        daemonProcess.destroyForcibly();
                    } else
                        LOG.info("zcashd shut down successfully");
                } catch (Exception bad) {
                    LOG.log(Level.WARNING,"Couldn't stop zcashd!",bad);
                }
            }
        });
    }
    
    private void doDispose() {
        SwingUtilities.invokeLater(() -> {dispose();});
    }
    
    public void setProgressText(final String text) {
        SwingUtilities.invokeLater(() -> {progressLabel.setText(text);});
    }
    
    private void performOSXBundleLaunch() throws IOException, InterruptedException {
        LOG.info("performing OSX Bundle-specific launch");
        File bundlePath = new File(System.getProperty("zcash.location.dir"));
        bundlePath = bundlePath.getCanonicalFile();
        
        // run "first-run.sh"
        File firstRun = new File(bundlePath,"first-run.sh");
        Process firstRunProcess = Runtime.getRuntime().exec(firstRun.getCanonicalPath());
        firstRunProcess.waitFor();
    }
}
