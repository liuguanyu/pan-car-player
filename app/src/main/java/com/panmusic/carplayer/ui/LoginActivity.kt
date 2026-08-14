package com.panmusic.carplayer.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.panmusic.carplayer.R
import com.panmusic.carplayer.viewmodel.AuthViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 百度网盘 OAuth 设备码登录页
 */
@AndroidEntryPoint
class LoginActivity : BaseCarActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    private lateinit var qrImageView: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var retryButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        qrImageView = findViewById(R.id.iv_qr_code)
        statusTextView = findViewById(R.id.tv_login_status)
        retryButton = findViewById(R.id.btn_retry)

        retryButton.setOnClickListener {
            authViewModel.requestDeviceCode()
        }

        observeState()
        authViewModel.requestDeviceCode()
    }

    private fun observeState() {
        lifecycleScope.launch {
            authViewModel.uiState.collectLatest { state ->
                when {
                    state.isLoggedIn -> {
                        statusTextView.text = getString(R.string.login_success)
                        Toast.makeText(this@LoginActivity, R.string.login_success, Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                    state.isPolling -> {
                        statusTextView.text = getString(R.string.login_waiting)
                        state.qrUrl.takeIf { it.isNotEmpty() }?.let { generateQrCode(it) }
                    }
                    state.isLoading -> {
                        statusTextView.text = getString(R.string.splash_loading)
                    }
                    state.error != null -> {
                        statusTextView.text = state.error
                        retryButton.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }
    }

    private fun generateQrCode(content: String) {
        lifecycleScope.launch {
            try {
                val hints = mapOf(
                    EncodeHintType.MARGIN to 2,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 280, 280, hints)
                val bitmap = Bitmap.createBitmap(280, 280, Bitmap.Config.RGB_565)

                for (x in 0 until 280) {
                    for (y in 0 until 280) {
                        bitmap.setPixel(x, y,
                            if (bitMatrix[x, y]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        )
                    }
                }
                qrImageView.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "二维码生成失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
