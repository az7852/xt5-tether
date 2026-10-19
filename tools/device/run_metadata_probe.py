#!/usr/bin/env python3
"""Read an existing photograph through production code, without installing a test package."""
import argparse
import os
from pathlib import Path
import shlex
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--photo',required=True,help='Existing JPEG/RAF path on the real device; read only')
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
sdk=Path(os.environ['ANDROID_HOME']);jdk=Path(os.environ['JAVA_HOME'])
android=sdk/'platforms/android-35/android.jar'
classes=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
probe=root/'build/device-metadata-probe';(probe/'classes').mkdir(parents=True,exist_ok=True)
subprocess.run([str(jdk/'bin/javac'),'--release','17','-cp',os.pathsep.join([str(android),str(classes)]),'-d',str(probe/'classes'),str(root/'tools/device/PhotoMetadataProbe.java')],check=True)
subprocess.run([str(jdk/'bin/java'),'-cp',str(sdk/'build-tools/35.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--lib',str(android),'--min-api','30','--output',str(probe/'probe.zip'),*map(str,probe.glob('classes/**/*.class'))],check=True)
adb=[str(sdk/'platform-tools/adb'),'-s',args.serial]
remote_probe='/data/local/tmp/xt5-metadata-probe.zip';remote_apk='/data/local/tmp/xt5-metadata-source.apk'
try:
    subprocess.run(adb+['push',str(probe/'probe.zip'),remote_probe],check=True)
    subprocess.run(adb+['push',str(apk),remote_apk],check=True)
    command='CLASSPATH='+shlex.quote(remote_probe+':'+remote_apk)+' app_process / io.github.xt5tether.PhotoMetadataProbe '+shlex.quote(args.photo)
    subprocess.run(adb+['shell',command],check=True)
finally:
    subprocess.run(adb+['shell','rm','-f',remote_probe,remote_apk],check=False)
