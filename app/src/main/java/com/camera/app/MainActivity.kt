package com.camera.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (hasCameraPermission()) {
            loadCameraFragment()
        } else {
            requestPermissions()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return

        if (hasCameraPermission()) {
            loadCameraFragment()
        } else {
            showPermissionDeniedDialog()
        }
    }

    /**
     * 权限被拒绝（尤其是勾了"不再询问"）时，原来这里只弹一个 Toast，
     * 界面就永远停在空白页。这里给一个可以跳转到系统设置重开的出口。
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要相机权限")
            .setMessage("拍照功能必须要有相机权限，请在系统设置中允许后重试。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("重试") { _, _ -> requestPermissions() }
            .setCancelable(false)
            .show()
    }

    private fun loadCameraFragment() {
        // 用 commitNow 同步提交，避免 onCreate 里刚提交、onResume 里又查不到而重复添加
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CameraFragment())
                .commitNow()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户去系统设置里授权后回到 App，这里自动把相机界面加载出来
        if (hasCameraPermission()) {
            loadCameraFragment()
        }
    }
}
