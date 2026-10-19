#!/usr/bin/env python3
"""Run the public-API/socket-lifetime check in a separate process on a real device."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
sdk = Path(os.environ["ANDROID_HOME"])
jdk = Path(os.environ["JAVA_HOME"])
android = sdk / "platforms/android-35/android.jar"
classes = root / "app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
probe = root / "build/device-tcp-probe"
(probe / "classes").mkdir(parents=True, exist_ok=True)
subprocess.run([str(jdk / "bin/javac"), "--release", "17", "-cp", os.pathsep.join([str(android), str(classes)]),
                "-d", str(probe / "classes"), str(root / "tools/device/TcpReceiveProbe.java")], check=True)
patterns = ["io/github/xt5tether/TcpReceiveHints*.class", "io/github/xt5tether/ptp/FujiWifiIo*.class",
            "io/github/xt5tether/ptp/PtpIo*.class", "io/github/xt5tether/ptp/PtpClient*.class",
            "io/github/xt5tether/ptp/PtpException*.class"]
inputs = [p for pattern in patterns for p in classes.glob(pattern)] + list(probe.glob("classes/**/*.class"))
subprocess.run([str(jdk / "bin/java"), "-cp", str(sdk / "build-tools/35.0.0/lib/d8.jar"), "com.android.tools.r8.D8",
                "--lib", str(android), "--min-api", "30", "--output", str(probe / "probe.zip"),
                *map(str, inputs)], check=True)
adb = [str(sdk / "platform-tools/adb"), "-s", args.serial]
remote = "/data/local/tmp/xt5-tcp-probe.zip"
try:
    subprocess.run(adb + ["push", str(probe / "probe.zip"), remote], check=True)
    subprocess.run(adb + ["shell", f"CLASSPATH={remote} app_process / io.github.xt5tether.TcpReceiveProbe"], check=True)
finally:
    subprocess.run(adb + ["shell", "rm", "-f", remote], check=False)
