#!/usr/bin/env python3
"""Build, inspect and drive OpenRain on an explicitly selected Android emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def output(args):
    return run(args, stdout=subprocess.PIPE, text=True).stdout.strip()


def sdk_path():
    configured = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if configured:
        return Path(configured)
    properties = ROOT / 'local.properties'
    if properties.exists():
        match = re.search(r'^sdk.dir=(.+)$', properties.read_text(), re.M)
        if match:
            return Path(match[1].strip())
    return Path.home() / 'Android' / 'Sdk'


SDK = sdk_path()
ADB = os.environ.get('ADB') or shutil.which('adb') or str(SDK / 'platform-tools' / 'adb')


def device(serial):
    lines = output([ADB, 'devices']).splitlines()[1:]
    ready = [line.split()[0] for line in lines if len(line.split()) >= 2 and line.split()[1] == 'device']
    if serial:
        if serial not in ready:
            raise RuntimeError(f'Device {serial} is not ready. Run adb devices.')
        return serial
    emulators = [item for item in ready if item.startswith('emulator-')]
    if len(emulators) != 1:
        raise RuntimeError('Start one emulator, or select a device explicitly with --serial SERIAL.')
    return emulators[0]


def wait_boot(adb):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if output(adb + ['shell', 'getprop', 'sys.boot_completed']) == '1':
            return
        time.sleep(2)
    raise RuntimeError('Android did not finish booting within 180 seconds.')


def metadata():
    path = ROOT / 'app/build/outputs/apk/debug/output-metadata.json'
    if not path.exists():
        raise RuntimeError('Build first: scripts/android-dev.py run')
    data = json.loads(path.read_text())
    return data['applicationId'], path.parent / data['elements'][0]['outputFile']


def capture(adb, folder):
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / 'screen.png').open('wb') as file:
        run(adb + ['exec-out', 'screencap', '-p'], stdout=file)
    run(adb + ['shell', 'uiautomator', 'dump', '/sdcard/window.xml'], stdout=subprocess.DEVNULL)
    run(adb + ['pull', '/sdcard/window.xml', folder / 'window.xml'], stdout=subprocess.DEVNULL)
    with (folder / 'logcat.txt').open('w') as file:
        run(adb + ['logcat', '-d', '-t', '2000'], stdout=file)
    print(folder)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('doctor', help='Show installed tools and available devices/AVDs')
    emulator = commands.add_parser('emulator', help='Run an AVD until Ctrl-C; no saved-state changes')
    emulator.add_argument('--avd', default='Pixel_8')
    emulator.add_argument('--window', action='store_true')
    launch = commands.add_parser('run', help='Build, install and launch the debug APK')
    launch.add_argument('--no-build', action='store_true')
    commands.add_parser('mirror', help='Open a scrcpy window')
    record = commands.add_parser('record', help='Record via scrcpy without a window')
    record.add_argument('--seconds', type=int, default=30)
    record.add_argument('--output', type=Path, default=ROOT / 'captures/radar.mp4')
    snap = commands.add_parser('capture', help='Save screenshot, UI hierarchy and recent logcat')
    snap.add_argument('--output', type=Path, default=ROOT / 'captures' / time.strftime('%Y%m%d-%H%M%S'))
    seek = commands.add_parser('seek', help='Move the radar timeline to a fraction between 0 and 1')
    seek.add_argument('fraction', type=float)
    seek.add_argument('--settle-seconds', type=float, default=1.0, help='Wait for bitmap decode/render before returning')
    commands.add_parser('test', help='Run connected Android regression tests')
    args = parser.parse_args()
    if args.command == 'doctor':
        print(f'SDK: {SDK}\nADB: {ADB}\nscrcpy: {shutil.which("scrcpy") or "missing"}')
        run([ADB, 'devices', '-l'])
        run([SDK / 'emulator/emulator', '-list-avds'])
        return
    if args.command == 'emulator':
        cmd = [SDK / 'emulator/emulator', '-avd', args.avd, '-no-audio', '-no-snapshot-save', '-gpu', 'software']
        if not args.window:
            cmd.append('-no-window')
        run(cmd)
        return
    serial = device(args.serial)
    adb = [ADB, '-s', serial]
    wait_boot(adb)
    if args.command in ('run', 'test'):
        if args.command == 'test':
            env = dict(os.environ, ANDROID_SERIAL=serial)
            run([ROOT / 'gradlew', ':app:connectedDebugAndroidTest', '--console=plain'], cwd=ROOT, env=env)
            return
        if not args.no_build:
            run([ROOT / 'gradlew', ':app:assembleDebug', '--console=plain'], cwd=ROOT)
        package, apk = metadata()
        run(adb + ['install', '-r', '-t', str(apk)])
        run(adb + ['shell', 'am', 'start', '-W', '-n', f'{package}/com.example.rainradar.MainActivity'])
    elif args.command in ('mirror', 'record'):
        scrcpy = shutil.which('scrcpy')
        if not scrcpy:
            raise RuntimeError('Install scrcpy with your system package manager first.')
        cmd = [scrcpy, '--serial', serial, '--no-audio']
        if args.command == 'record':
            if args.seconds <= 0:
                raise RuntimeError('--seconds must be positive.')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            if args.output.exists():
                raise RuntimeError('Recording already exists; select a new --output path.')
            cmd += ['--no-playback', '--record', str(args.output), '--time-limit', str(args.seconds)]
        run(cmd)
    elif args.command == 'capture':
        capture(adb, args.output)
    elif args.command == 'seek':
        if not 0 <= args.fraction <= 1:
            raise RuntimeError('fraction must be between 0 and 1.')
        run(adb + ['shell', 'uiautomator', 'dump', '/sdcard/window.xml'], stdout=subprocess.DEVNULL)
        tree = ET.fromstring(output(adb + ['shell', 'cat', '/sdcard/window.xml']))
        sliders = [node for node in tree.iter('node') if node.get('content-desc') == 'Radar-Zeit']
        if len(sliders) != 1:
            raise RuntimeError('Radar timeline not visible. Wait for loading, dismiss dialogs, and show controls.')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', sliders[0].get('bounds')))
        # The custom 30dp thumb fills the slider semantics height. Its center
        # stops one radius inside either edge of the slider bounds.
        inset = round((y2 - y1) / 2)
        x = round(x1 + inset + args.fraction * (x2 - x1 - 2 * inset))
        run(adb + ['shell', 'input', 'tap', str(x), str((y1 + y2) // 2)])
        time.sleep(max(0, min(args.settle_seconds, 30)))
        print(f'Selected timeline position {args.fraction:.0%}; use capture to inspect the rendered frame.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
