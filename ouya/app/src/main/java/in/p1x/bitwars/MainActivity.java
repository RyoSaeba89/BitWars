package in.p1x.bitwars;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import org.xwalk.core.XWalkActivity;
import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkView;

/**
 * BitWars on OUYA — thin Crosswalk (Chromium 53) shell that runs the bundled
 * ImpactJS HTML5 game from assets/www. Crosswalk provides the W3C Gamepad API
 * that the OUYA's stock Android-4.1 WebView lacks, so the game's gamepad.js
 * works as written.
 */
public class MainActivity extends XWalkActivity {

    private XWalkView mXWalkView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // setContentView happens in onXWalkReady (runtime must be up first).
    }

    @Override
    protected void onXWalkReady() {
        // SurfaceView-backed view = best Canvas2D perf on Tegra 3.
        XWalkPreferences.setValue(XWalkPreferences.ANIMATABLE_XWALK_VIEW, false);
        // chrome://inspect over adb while we validate on the device.
        XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, true);

        mXWalkView = new XWalkView(this, this);
        setContentView(mXWalkView);
        hideSystemUi();
        mXWalkView.load("file:///android_asset/www/index.html", null);
    }

    private void hideSystemUi() {
        mXWalkView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mXWalkView != null) {
            mXWalkView.resumeTimers();
            mXWalkView.onShow();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mXWalkView != null) {
            mXWalkView.pauseTimers();
            mXWalkView.onHide();
        }
    }

    @Override
    public void onDestroy() {
        if (mXWalkView != null) {
            mXWalkView.onDestroy();
        }
        super.onDestroy();
    }
}
