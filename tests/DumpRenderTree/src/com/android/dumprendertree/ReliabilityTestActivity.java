package com.android.dumprendertree;

import android.app.Activity;
import android.app.ActivityThread;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

public class ReliabilityTestActivity extends Activity {
    
    public static final String TEST_URL_ACTION = "com.andrdoid.dumprendertree.TestUrlAction";
    public static final String PARAM_URL = "URL";
    public static final String PARAM_TIMEOUT = "Timeout";
    public static final int RESULT_TIMEOUT = 0xDEAD;
    public static final int MSG_TIMEOUT = 0xC001;
    public static final int MSG_NAVIGATE = 0xC002;
    
    private static final String LOGTAG = "ReliabilityTestActivity";
    
    private WebView webView;
    private SimpleWebViewClient webViewClient;
    private SimpleChromeClient chromeClient;
    private Handler handler;
    private boolean timeoutFlag;
    private boolean pageDone;
    private Object pageDoneLock;
    private int pageStartCount;
    private int manualDelay;
    private PageDoneRunner pageDoneRunner = new PageDoneRunner();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.v(LOGTAG, "onCreate, inst=" + Integer.toHexString(hashCode()));
        
        LinearLayout contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        setContentView(contentView);
        setTitle("Idle");
        
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setLayoutAlgorithm(LayoutAlgorithm.NORMAL);
        
        webViewClient = new SimpleWebViewClient();
        chromeClient = new SimpleChromeClient();
        webView.setWebViewClient(webViewClient);
        webView.setWebChromeClient(chromeClient);
        
        contentView.addView(webView, new LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT, 0.0f));
        
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_TIMEOUT:
                        handleTimeout();
                        return;
                    case MSG_NAVIGATE:
                        manualDelay = msg.arg2;
                        navigate((String)msg.obj, msg.arg1);
                        return;
                }
            }
        };
        
        pageDoneLock = new Object();
    }
    
    public void reset() {
        synchronized (pageDoneLock) {
            pageDone = false;
        }
        timeoutFlag = false;
        pageStartCount = 0;
        chromeClient.resetJsTimeout();
    }
    
    private void navigate(String url, int timeout) {
        if(url == null) {
            Log.v(LOGTAG, "URL is null, cancelling...");
            finish();
        }
        webView.stopLoading();
        Log.v(LOGTAG, "Navigating to URL: " + url);
        webView.loadUrl(url);
        
        if(timeout != 0) {
            //set a timer with specified timeout (in ms)
            handler.sendMessageDelayed(handler.obtainMessage(MSG_TIMEOUT),
                    timeout);
        }
    }
    
    @Override
    protected void onDestroy() {
        Log.v(LOGTAG, "onDestroy, inst=" + Integer.toHexString(hashCode()));
        super.onDestroy();
    }
    
    private boolean isPageDone() {
        synchronized (pageDoneLock) {
            return pageDone;
        }
    }
    
    private void setPageDone(boolean pageDone) {
        synchronized (pageDoneLock) {
            this.pageDone = pageDone;
            pageDoneLock.notifyAll();
        }
    }
    
    private void handleTimeout() {
        int progress = webView.getProgress();
        webView.stopLoading();
        Log.v(LOGTAG, "Page timeout triggered, progress = " + progress);
        timeoutFlag = true;
    }
    
    public boolean waitUntilDone() {
        validateNotAppThread();
        synchronized (pageDoneLock) {
            while(!isPageDone()) {
                try {
                    pageDoneLock.wait();
                } catch (InterruptedException ie) {
                    //no-op
                }
            }
        }
        return timeoutFlag;
    }
    
    public Handler getHandler() {
        return handler;
    }
    
    private final void validateNotAppThread() {
        if (ActivityThread.currentActivityThread() != null) {
            throw new RuntimeException(
                "This method can not be called from the main application thread");
        }
    }

    class SimpleWebViewClient extends WebViewClient {
        
        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            Log.v(LOGTAG, "Received WebCore error: code=" + errorCode
                    + ", description=" + description
                    + ", url=" + failingUrl);
        }
        
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            //ignore certificate error
            Log.v(LOGTAG, "Received SSL error: " + error.toString());
            handler.proceed();
        }
        
        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host,
                String realm) {
            //cancel http auth request
            handler.cancel();
        }
        
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageStartCount++;
            Log.v(LOGTAG, "onPageStarted: " + url);
        }
        
        @Override
        public void onPageFinished(WebView view, String url) {
            Log.v(LOGTAG, "onPageFinished: " + url);
            handler.postDelayed(new WebViewStatusChecker(), 500);
        }
    }
    
    class SimpleChromeClient extends WebChromeClient {
        
        private int timeoutCounter = 0;
        
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }
        
        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }
        
        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                JsPromptResult result) {
            result.confirm();
            return true;
        }
        
        @Override
        public boolean onJsTimeout() {
            timeoutCounter++;
            Log.v(LOGTAG, "JavaScript timeout, count=" + timeoutCounter);
            return timeoutCounter > 2;
        }
        
        public void resetJsTimeout() {
            timeoutCounter = 0;
        }
        
        @Override
        public void onReceivedTitle(WebView view, String title) {
            ReliabilityTestActivity.this.setTitle(title);
        }
    }
    
    class WebViewStatusChecker implements Runnable {
        
        private int initialStartCount;
        
        public WebViewStatusChecker() {
            initialStartCount = pageStartCount;
        }
        
        public void run() {
            if (initialStartCount == pageStartCount) {
                //perform cleanup
                handler.removeMessages(MSG_TIMEOUT);
                webView.stopLoading();
                handler.postDelayed(pageDoneRunner, manualDelay);
            }
        }
    }
    
    class PageDoneRunner implements Runnable {
        
        public void run() {
            Log.v(LOGTAG, "Finishing URL: " + webView.getUrl());
            setPageDone(true);
        }
    }
}
