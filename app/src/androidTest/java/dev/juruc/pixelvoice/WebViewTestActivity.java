package dev.juruc.pixelvoice;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.LinearLayout;

/**
 * Hosts a WebView with two HTML text inputs, mirroring the Chrome browser field scenario
 * where {@code getSurroundingText} returns {@code offset = -1}. Runs in
 * {@code :keyboard_test_host} process for independent-UID keyboard testing.
 */
public final class WebViewTestActivity extends Activity {
    static final String FIRST_EDITOR = "WebView test first editor";
    static final String SECOND_EDITOR = "WebView test second editor";
    static final String ORIGINAL_TEXT = "Original: ";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(false);
        webView.setContentDescription("WebView test container");
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "</head><body style='font-size:20px;padding:12px'>"
                + "<label for='first'>" + FIRST_EDITOR + "</label><br>"
                + "<input id='first' type='text' value='" + ORIGINAL_TEXT
                + "' style='box-sizing:border-box;width:100%;font-size:20px;padding:8px'>"
                + "<br><br><label for='second'>" + SECOND_EDITOR + "</label><br>"
                + "<input id='second' type='text' value='" + ORIGINAL_TEXT
                + "' style='box-sizing:border-box;width:100%;font-size:20px;padding:8px'>"
                + "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        root.addView(webView);
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
