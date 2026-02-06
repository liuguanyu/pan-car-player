package com.baidu.carplayer;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.AuthInfo;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class LoginActivity extends AppCompatActivity {

    private ImageView qrCodeImage;
    private TextView userCodeText;
    private TextView waitingText;
    private TextView errorText;
    private Button reloadButton;
    private ProgressBar qrCodeProgress;

    private BaiduAuthService authService;
    private Handler handler;
    private Runnable pollingRunnable;
    private String deviceCode;
    private String userCode;
    private String verificationUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        initViews();
        initAuthService();
        startDeviceCodeFlow();
    }

    private void initViews() {
        qrCodeImage = findViewById(R.id.qr_code_image);
        userCodeText = findViewById(R.id.user_code_text);
        waitingText = findViewById(R.id.waiting_text);
        errorText = findViewById(R.id.error_text);
        reloadButton = findViewById(R.id.reload_button);
        qrCodeProgress = findViewById(R.id.qr_code_progress);

        reloadButton.setOnClickListener(v -> {
            errorText.setVisibility(View.GONE);
            reloadButton.setVisibility(View.GONE);
            startDeviceCodeFlow();
        });

        handler = new Handler(Looper.getMainLooper());
    }

    private void initAuthService() {
        authService = BaiduAuthService.getInstance(this);
    }

    private void startDeviceCodeFlow() {
        showLoading(true);
        
        authService.getDeviceCode(new BaiduAuthService.DeviceCodeCallback() {
            @Override
            public void onSuccess(String deviceCode, String userCode, String verificationUrl, int expiresIn) {
                LoginActivity.this.deviceCode = deviceCode;
                LoginActivity.this.userCode = userCode;
                LoginActivity.this.verificationUrl = verificationUrl;

                runOnUiThread(() -> {
                    showLoading(false);
                    // 使用包含user_code的完整URL生成二维码，实现扫码后直接登录
                    String fullUrl = verificationUrl + "?code=" + userCode;
                    displayQRCode(fullUrl);
                    // 隐藏user_code显示，因为已经包含在二维码中
                    userCodeText.setVisibility(View.GONE);
                    waitingText.setVisibility(View.VISIBLE);
                    startPolling();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    showError(getString(R.string.login_failed, error));
                });
            }
        });
    }

    private void displayQRCode(String url) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 280, 280);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }

            qrCodeImage.setImageBitmap(bitmap);
            qrCodeImage.setVisibility(View.VISIBLE);
        } catch (WriterException e) {
            showError("生成二维码失败: " + e.getMessage());
        }
    }

    private void startPolling() {
        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                authService.pollToken(deviceCode, new BaiduAuthService.TokenCallback() {
                    @Override
                    public void onSuccess(AuthInfo authInfo) {
                        runOnUiThread(() -> {
                            stopPolling();
                            waitingText.setText(getString(R.string.login_success));
                            // 认证信息已在Service中保存
                            // 跳转到主界面
                            navigateToMain();
                        });
                    }

                    @Override
                    public void onPending() {
                        // 继续轮询
                        handler.postDelayed(pollingRunnable, 6000); // 6秒轮询间隔
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            stopPolling();
                            showError(getString(R.string.login_failed, error));
                        });
                    }
                });
            }
        };

        handler.postDelayed(pollingRunnable, 6000);
    }

    private void stopPolling() {
        if (pollingRunnable != null) {
            handler.removeCallbacks(pollingRunnable);
            pollingRunnable = null;
        }
    }

    private void showLoading(boolean loading) {
        if (loading) {
            qrCodeProgress.setVisibility(View.VISIBLE);
            qrCodeImage.setVisibility(View.GONE);
            userCodeText.setVisibility(View.GONE);
            waitingText.setVisibility(View.GONE);
            errorText.setVisibility(View.GONE);
            reloadButton.setVisibility(View.GONE);
        } else {
            qrCodeProgress.setVisibility(View.GONE);
        }
    }

    private void showError(String error) {
        errorText.setText(error);
        errorText.setVisibility(View.VISIBLE);
        reloadButton.setVisibility(View.VISIBLE);
        waitingText.setVisibility(View.GONE);
    }

    private void navigateToMain() {
        // 延迟跳转，让用户看到成功提示
        handler.postDelayed(() -> {
            // 显式跳转到MainActivity，而不是finish
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            // 清除任务栈，防止用户按返回键回到登录页
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}