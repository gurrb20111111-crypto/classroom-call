# -*- coding: utf-8 -*-
"""
교실 호출 수신기 APK 빌드 스크립트 (Android Studio·Gradle 없이 SDK 도구만 직접 호출)

  aapt2 compile/link → javac → d8 → classes.dex 넣기 → zipalign → apksigner

실행:  빌드.bat   (또는  python build.py)
결과:  dist\교실호출.apk   ← USB로 전자칠판에 옮겨 설치
"""
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APP = ROOT / "app"
DIST = ROOT / "dist"
KEYS = ROOT / "keys"

# 안드로이드 SDK 도구(aapt2 등)는 경로에 한글이 있으면 '파일을 찾을 수 없습니다'로 실패한다.
#   → SDK는 C:\android-sdk 에 두고, 빌드도 영문 경로에서 하고, 결과 APK만 이 폴더로 가져온다.
SDK = Path(os.environ.get("ANDROID_SDK") or r"C:\android-sdk")
WORK = Path(os.environ.get("CLASSCALL_WORK") or r"C:\classcall-build")
BUILD = WORK / "build"
SRC = WORK / "app"

BUILD_TOOLS = SDK / "build-tools" / "34.0.0"
ANDROID_JAR = SDK / "platforms" / "android-34" / "android.jar"
JAVA_HOME = Path(os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot")

APK_NAME = "교실호출.apk"
MIN_SDK = "21"
TARGET_SDK = "29"


def run(args, **kw):
    """외부 도구 실행. 실패하면 출력 그대로 보여주고 멈춘다."""
    p = subprocess.run([str(a) for a in args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", **kw)
    if p.returncode != 0:
        print("\n[실패] " + " ".join(str(a) for a in args[:3]) + " …")
        print(p.stdout)
        print(p.stderr)
        sys.exit(1)
    return p


def need(path, what):
    if not Path(path).exists():
        print("[없음] {} — {}".format(path, what))
        sys.exit(1)


def main():
    need(ANDROID_JAR, "android.jar (sdkmanager로 platforms;android-34 설치)")
    need(BUILD_TOOLS, "build-tools;34.0.0")
    need(JAVA_HOME / "bin" / "javac.exe", "JDK 17 (JAVA_HOME 확인)")

    # 한글 경로 회피: 소스를 영문 작업폴더로 복사해 거기서 빌드한다.
    if WORK.exists():
        shutil.rmtree(WORK)
    shutil.copytree(APP, SRC)
    (BUILD / "gen").mkdir(parents=True)
    (BUILD / "classes").mkdir(parents=True)
    (BUILD / "dex").mkdir(parents=True)
    DIST.mkdir(exist_ok=True)

    # 1) 리소스 컴파일 + 링크(빈 껍데기 apk 생성, R.java 도 여기서 나옴)
    print("① 리소스 컴파일…")
    res_zip = BUILD / "res.zip"
    run([BUILD_TOOLS / "aapt2.exe", "compile", "--dir", SRC / "res", "-o", res_zip])

    print("② 리소스 링크…")
    base_apk = BUILD / "base.apk"
    run([BUILD_TOOLS / "aapt2.exe", "link",
         "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", SRC / "AndroidManifest.xml",
         "--java", BUILD / "gen",
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--auto-add-overlay",
         res_zip])

    # 2) 자바 컴파일
    print("③ 자바 컴파일…")
    srcs = [str(p) for p in list((SRC / "src").rglob("*.java")) + list((BUILD / "gen").rglob("*.java"))]
    run([JAVA_HOME / "bin" / "javac.exe",
         "-source", "8", "-target", "8", "-nowarn", "-Xlint:-options",
         "-encoding", "UTF-8",
         "-bootclasspath", ANDROID_JAR,
         "-classpath", ANDROID_JAR,
         "-d", BUILD / "classes"] + srcs)

    # 3) dex 변환
    print("④ dex 변환…")
    classes = [str(p) for p in (BUILD / "classes").rglob("*.class")]
    run([BUILD_TOOLS / "d8.bat", "--release", "--min-api", MIN_SDK,
         "--lib", ANDROID_JAR, "--output", BUILD / "dex"] + classes)

    # 4) classes.dex 를 apk 안에 넣기
    print("⑤ 패키징…")
    unaligned = BUILD / "app-unaligned.apk"
    shutil.copy(base_apk, unaligned)
    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as z:
        for dex in sorted((BUILD / "dex").glob("*.dex")):
            z.write(dex, dex.name)

    # 5) 정렬 + 서명
    print("⑥ 정렬·서명…")
    aligned = BUILD / "app-aligned.apk"
    run([BUILD_TOOLS / "zipalign.exe", "-f", "4", unaligned, aligned])

    KEYS.mkdir(exist_ok=True)
    ks = KEYS / "classcall.jks"
    if not ks.exists():
        print("   (서명키가 없어 새로 만듭니다 — 앞으로 계속 이 키로 업데이트 설치됩니다)")
        run([JAVA_HOME / "bin" / "keytool.exe", "-genkeypair",
             "-keystore", ks, "-alias", "classcall",
             "-storepass", "classcall", "-keypass", "classcall",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=okjung classcall, OU=school, O=school, L=Seoul, C=KR"])

    out = DIST / APK_NAME
    if out.exists():
        out.unlink()
    run([BUILD_TOOLS / "apksigner.bat", "sign",
         "--ks", ks, "--ks-pass", "pass:classcall", "--key-pass", "pass:classcall",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--out", out, aligned])

    size = out.stat().st_size / 1024.0
    print("\n완성 ✓  {}  ({:.0f} KB)".format(out, size))
    print("USB로 전자칠판에 복사 → File Commander로 이 파일 실행 → '알 수 없는 출처 허용' → 설치")


if __name__ == "__main__":
    main()
