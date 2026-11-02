# 아이랑 드라이브

Pleos 차량 기어 정보와 보호자의 직접 확인을 연결해 출발 준비부터 내리기 전 확인까지 돕는 차량용 앱입니다.

## 앱과 APK

- package: `com.airang.drive`
- version: `0.13.0` (13)
- 제출용 서명 release APK: `apk/최수용_아이랑드라이브_release.apk`
- APK SHA256: `apk/SHA256SUMS.txt`
- 서명 검증 결과: `apk/SIGNATURE.txt`
- 빌드 결과 및 설치 검증 여부: `BUILD-RESULT.json`

APK 파일명은 개인 참가자 최수용의 제출용 이름으로 정리했습니다. 파일명 변경은 APK 내부 내용이나 서명을 바꾸지 않습니다.

## 테스트 환경과 필요한 설정

Windows Android Studio, Android SDK Platform 37 및 호환 Build-Tools, Pleos Connect v2.1.0 에뮬레이터(Android API 34)에서 개발했습니다. minSdk 28, target/compile 37입니다. 이 저장소에는 SDK나 에뮬레이터 이미지가 없습니다. Pleos 문서에 따라 SDK 및 Pleos 에뮬레이터를 준비하세요.

의존성은 Google Maven, Maven Central, `https://nexus-playground.pleos.ai/repository/maven-releases/`에서 받습니다. Vehicle SDK는 `ai.pleos.playground:Vehicle:2.3.2`입니다. 일부 사용 API는 deprecated 상태이며 권장 API 전환은 후속 검토 항목입니다.

## 소스 실행

1. Android Studio에서 저장소 루트를 엽니다.
2. Android SDK 37과 Build-Tools를 설치하고 Gradle Sync를 완료합니다.
3. Gradle 9.6.0 / AGP 9.4.1 프로젝트입니다. daemon JVM 기준은 JDK 25입니다. 최초 실행 시 Gradle 및 JDK/의존성 다운로드를 위한 네트워크가 필요할 수 있습니다.
4. Pleos Connect 에뮬레이터를 실행하고 실행 대상으로 선택합니다.
5. `app`을 Run합니다. 앱 실행 시 P 상태에서 사용합니다.

Windows 명령줄 빌드:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

프로젝트에 서명키는 없습니다. 위 명령은 unsigned release를 만듭니다. 배포용으로는 개인 소유의 서명키로 zipalign 이후 apksigner sign을 수행해야 합니다. 기존 제출 APK는 이미 서명한 파일이며, 추가 서명 없이 설치할 수 있습니다.

## 제출 APK 설치와 실행

깨끗한 Pleos 에뮬레이터 또는 동일한 release 키로 서명된 앱이 설치된 기기에서:

```powershell
adb -s <Pleos-emulator-serial> install -r "apk/최수용_아이랑드라이브_release.apk"
adb -s <Pleos-emulator-serial> shell am start -W -n com.airang.drive/.MainActivity
```

기존 debug 앱과 서명키가 다르면 업데이트 설치가 거절됩니다. 기존 데이터가 있는 앱을 지우지 말고 별도의 테스트용 Pleos 에뮬레이터를 사용하세요. 일반 Android 휴대폰에서는 Pleos 차량 서비스가 없어 기어 연동을 검증할 수 없습니다.

## 핵심 확인 절차

1. P에서 앱 실행, 출발 준비 항목 선택/저장.
2. 이동 시작. 서비스 알림과 기어 수신 확인.
3. 필요한 경우 `adb -s <serial> forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`의 Motion / Gear Selection에서 D 또는 R을 선택하고 6초 이상 대기.
4. P로 바꾸고 4초 이상 기다린 뒤 앱으로 돌아와 이동 종료 여부 질문 확인.
5. 하차 확인 2/3이면 종료 불가, 3/3과 최근 P 조건이면 종료 가능.
6. 완료 화면, 다음 이동 준비, 앱 재진입 및 설정 유지 확인.

D/R 등 P 이외의 상태에서는 Pleos의 주행 중 화면 사용 제한을 유지합니다. 주행 중 화면을 조작하기 위해 이 제한을 해제하지 않습니다.

## 데이터와 구현 범위

준비 목록 설정, 활성 이동 상태, 최근 체크리스트 완료 기록을 앱 내부에 저장합니다. 해당 상태 저장 파일은 OS 백업에서 제외합니다. 아이 탑승·하차·실내 잔류, 목적지, 실제 이동 거리를 자동 감지하지 않습니다. 강제 종료·전원 종료 후 감시를 보장하지 않습니다. 실제 통신 장애 복구, 장시간 구동 및 실차 시험은 후속 검증 대상입니다.

## 검증 결과 (2026-11-02)

제출 APK와 설치 기록의 SHA256이 일치합니다. 빌드 도구 기록상 단위 테스트, lintRelease, release 빌드, APK 서명과 정렬, non-debuggable 검사를 통과했습니다. Pleos 테스트 에뮬레이터에서 APK 설치 및 앱 실행을 확인했고, 사용자가 위 5개 핵심 기능 흐름의 정상 동작을 확인했습니다.

- 사용자 확인: `verification/USER-TEST-RESULT.json`
- 원본 빌드 및 설치 기록: `verification/*-original.json`
- 자동 설치 도구의 프로세스 관측 값은 false였습니다. 이 원본 값은 유지했으며 이후 사용자의 실제 실행/기능 확인과 구분합니다.
- 실차 시험, 장시간 안정성 및 실제 통신 장애 시험은 수행하지 않았습니다.
- 소스 39개는 빌드 기준 v13 소스와 바이트 단위 해시로 대조했습니다.

## v13 실행 오류 대응

v12 서명 release는 Startup/Lifecycle 초기화에서 Kotlin 컬렉션 클래스 접근 오류로 종료됐습니다. v13은 앱 로직을 유지하고 release R8 최적화를 비활성화했으며, 사용자가 실행과 핵심 기능 정상 동작을 확인한 버전입니다. APK 크기는 커질 수 있습니다. debuggable은 활성화하지 않았으며 서명 release로 빌드합니다. 최적화된 배포는 별도 원인 분석과 재검증 후 검토합니다. 빌드 성공은 실제 실행 성공을 의미하지 않습니다.
