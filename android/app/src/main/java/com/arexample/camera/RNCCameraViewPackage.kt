package com.arexample.camera

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * React Native 카메라 패키지
 * 
 * React Native 애플리케이션에 카메라 기능을 제공하는 패키지입니다.
 * React Native의 네이티브 모듈 시스템에 카메라 ViewManager를 등록합니다.
 * 
 * 사용 예시:
 * ```kotlin
 * // MainApplication.kt에서 패키지 등록
 * override fun getPackages(): List<ReactPackage> =
 *     PackageList(this).packages.apply {
 *         add(RNCCameraViewPackage())
 *     }
 * ```
 */
class RNCCameraViewPackage : ReactPackage {
    
    /**
     * 네이티브 모듈 목록을 생성합니다.
     * 
     * 현재 이 패키지는 네이티브 모듈을 제공하지 않습니다.
     * 
     * @param reactContext 리액트 애플리케이션 컨텍스트
     * @return 빈 네이티브 모듈 목록
     */
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> = 
        emptyList()

    /**
     * ViewManager 목록을 생성합니다.
     * 
     * 카메라 ViewManager를 React Native에 제공합니다.
     * 
     * @param reactContext 리액트 애플리케이션 컨텍스트
     * @return RNCCameraViewManager를 포함한 ViewManager 목록
     */
    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(RNCCameraViewManager())
} 