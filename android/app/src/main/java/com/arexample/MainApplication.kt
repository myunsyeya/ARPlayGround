package com.arexample

import android.app.Application
import com.arexample.camera.RNCCameraViewPackage
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.react.soloader.OpenSourceMergedSoMapping
import com.facebook.soloader.SoLoader

/**
 * 메인 애플리케이션 클래스
 * 
 * React Native 애플리케이션의 진입점 역할을 하는 애플리케이션 클래스입니다.
 * React Native 호스트를 초기화하고 필요한 패키지를 등록합니다.
 * 
 * ReactApplication 인터페이스를 구현하여 React Native가 안드로이드 애플리케이션과
 * 통신할 수 있도록 합니다.
 */
class MainApplication : Application(), ReactApplication {

  /**
   * React Native 호스트
   * 
   * React Native 엔진 및 React 트리를 관리하는 클래스입니다.
   * JavaScript 번들 로딩, 네이티브 모듈 등록 등을 처리합니다.
   */
  override val reactNativeHost: ReactNativeHost =
      object : DefaultReactNativeHost(this) {
        /**
         * React Native 패키지 목록을 반환합니다.
         * 
         * 자동 링크되지 않는 패키지는 여기에서 수동으로 추가할 수 있습니다.
         * RNCCameraViewPackage를 추가하여 카메라 기능을 제공합니다.
         * 
         * @return React Native 패키지 목록
         */
        override fun getPackages(): List<ReactPackage> =
            PackageList(this).packages.apply {
              // Packages that cannot be autolinked yet can be added manually here, for example:
              // add(MyReactNativePackage())
              add(RNCCameraViewPackage())
            }

        /**
         * JavaScript 메인 모듈 이름을 반환합니다.
         * 
         * @return JavaScript 메인 모듈 이름
         */
        override fun getJSMainModuleName(): String = "index"

        /**
         * 개발자 지원 모드 활성화 여부를 반환합니다.
         * 
         * @return 개발자 지원 모드 활성화 여부
         */
        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

        /**
         * 새로운 아키텍처(New Architecture) 활성화 여부
         */
        override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        
        /**
         * Hermes 엔진 활성화 여부
         */
        override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
      }

  /**
   * React 호스트
   * 
   * React Native 애플리케이션의 호스트 인터페이스입니다.
   */
  override val reactHost: ReactHost
    get() = getDefaultReactHost(applicationContext, reactNativeHost)

  /**
   * 애플리케이션이 생성될 때 호출됩니다.
   * 
   * SoLoader를 초기화하고, 필요한 경우 새로운 아키텍처의
   * 네이티브 진입점을 로드합니다.
   */
  override fun onCreate() {
    super.onCreate()
    SoLoader.init(this, OpenSourceMergedSoMapping)
    if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      // 새로운 아키텍처(New Architecture)를 사용하는 경우 앱의 네이티브 진입점을 로드합니다.
      load()
    }
  }
}
