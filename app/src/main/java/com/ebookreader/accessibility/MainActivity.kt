package com.ebookreader.accessibility

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ebookreader.accessibility.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    companion object {
        const val REQUEST_OVERLAY_PERMISSION = 1001
        const val REQUEST_MEDIA_PROJECTION = 1002
        const val REQUEST_RECORD_AUDIO = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnStartService.setOnClickListener {
            if (checkAllPermissions()) {
                requestMediaProjection()
            } else {
                checkPermissions()
            }
        }

        binding.btnStopService.setOnClickListener {
            stopOverlayService()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun checkPermissions() {
        // 1. 오버레이 권한 확인
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("오버레이 권한 필요")
                .setMessage("다른 앱 위에 표시하기 위해 오버레이 권한이 필요합니다.")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                }
                .show()
            return
        }

        // 2. 마이크 권한
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        // 3. 접근성 서비스 활성화 안내
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("접근성 서비스 활성화 필요")
                .setMessage("전자책 앱 감지를 위해 접근성 서비스를 활성화해주세요.\n\n설정 > 접근성 > 설치된 앱 > 전자책 보조 리더")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("나중에", null)
                .show()
        }

        updatePermissionStatus()
    }

    private fun checkAllPermissions(): Boolean {
        return Settings.canDrawOverlays(this) &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun requestMediaProjection() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        updateServiceStatus(false)
    }

    private fun updatePermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val accessibilityOk = isAccessibilityServiceEnabled()

        binding.statusOverlay.text = if (overlayOk) "✓ 오버레이 권한" else "✗ 오버레이 권한 필요"
        binding.statusMic.text = if (micOk) "✓ 마이크 권한" else "✗ 마이크 권한 필요"
        binding.statusAccessibility.text = if (accessibilityOk) "✓ 접근성 서비스" else "△ 접근성 서비스 (선택)"

        binding.btnStartService.isEnabled = overlayOk && micOk
    }

    private fun updateServiceStatus(running: Boolean) {
        binding.tvServiceStatus.text = if (running) "서비스 실행 중" else "서비스 중지됨"
        binding.btnStartService.isEnabled = !running
        binding.btnStopService.isEnabled = running
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> checkPermissions()
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, OverlayService::class.java).apply {
                        putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                    }
                    startForegroundService(intent)
                    updateServiceStatus(true)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }
}
