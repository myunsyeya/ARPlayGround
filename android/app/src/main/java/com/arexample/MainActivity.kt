package com.arexample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * 메인 액티비티
 * 
 * React Native 애플리케이션의 진입점 역할을 하는 액티비티입니다.
 * 카메라 권한 요청 및 처리 로직을 포함합니다.
 * 
 * 이 액티비티는 앱이 시작될 때 카메라 권한을 자동으로 요청합니다.
 */
class MainActivity : ReactActivity() {

  /**
   * 카메라 권한 요청 코드
   */
  private val CAMERA_PERMISSION_REQUEST_CODE = 1001

  /**
   * JavaScript에서 등록된 메인 컴포넌트의 이름을 반환합니다.
   * 이 이름은 컴포넌트 렌더링 스케줄링에 사용됩니다.
   * 
   * @return 메인 컴포넌트 이름
   */
  override fun getMainComponentName(): String = "ArExample"

  /**
   * ReactActivityDelegate의 인스턴스를 반환합니다.
   * 
   * DefaultReactActivityDelegate를 사용하여 단일 boolean 플래그 [fabricEnabled]로
   * New Architecture를 활성화할 수 있습니다.
   * 
   * @return ReactActivityDelegate 인스턴스
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
      
  /**
   * 액티비티가 생성될 때 호출됩니다.
   * 
   * 카메라 권한을 확인하고 필요한 경우 요청합니다.
   * 
   * @param savedInstanceState 이전 상태 정보
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    checkCameraPermission()
  }
  
  /**
   * 카메라 권한을 확인합니다.
   * 
   * 권한이 없는 경우 사용자에게 요청합니다.
   */
  private fun checkCameraPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }
  }
  
  /**
   * 권한 요청 결과 처리
   * 
   * 사용자가 권한 요청에 응답한 후 호출됩니다.
   * 
   * @param requestCode 요청 코드
   * @param permissions 요청된 권한 배열
   * @param grantResults 권한 부여 결과 배열
   */
  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
      if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Log.d("MainActivity", "카메라 권한이 허용되었습니다")
      } else {
        Log.e("MainActivity", "카메라 권한이 거부되었습니다")
      }
    }
  }
}
