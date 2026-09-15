#!/usr/bin/env python3
"""Bounded HTTPS radar load test; run only against a server you administer."""
import argparse
import concurrent.futures
import datetime as dt
import http.client
import json
import math
import ssl
import subprocess
import threading
import time
import urllib.parse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--ssh', required=True, help='SSH host for resource guard')
    parser.add_argument('--users', type=int, nargs='+', default=[10, 25, 50])
    parser.add_argument('--frames', type=int, default=60)
    parser.add_argument('--timeout', type=float, default=20)
    parser.add_argument('--base', help='Fixed ISO UTC generation, to separate warm-cache tests from a time change')
    parser.add_argument('--warmup', action='store_true', help='Load one full session immediately before measured stages (45s request timeout)')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.frames <= 60 or any(u < 1 or u > 50 for u in args.users):
        parser.error('Use 1–60 frames and 1–50 users per stage')
    endpoint = urllib.parse.urlsplit(args.url)
    if endpoint.scheme != 'https':
        parser.error('HTTPS required')
    # Share immutable TLS configuration instead of parsing CA certificates
    # hundreds of times on the load generator during a connection burst.
    tls_context = ssl.create_default_context()
    base = (int(time.time()) - 600) // 300 * 300
    if args.base:
        base = int(dt.datetime.fromisoformat(args.base.replace('Z', '+00:00')).timestamp())
    def iso(value):
        return dt.datetime.fromtimestamp(value, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    # Forecast first, matching the app; one shared generation for the whole test.
    offsets = list(range(24)) + list(range(-1, -37, -1))
    paths = [endpoint.path + '?' + urllib.parse.urlencode(dict(
        time=iso(base + i * 300), base=iso(base), width=1920, height=2084,
    )) for i in offsets[:args.frames]]
    stop = threading.Event()
    finished = threading.Event()
    samples = []
    report = dict(url=args.url, started=iso(time.time()), base=iso(base),
                  requests_per_user=args.frames, parallel_requests_per_user=8,
                  stages=[], samples=samples)

    def monitor():
        while not finished.is_set():
            try:
                result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5',
                    args.ssh, 'cat /proc/meminfo; docker stats --no-stream --format "{{json .}}" dwd-radar-proxy'],
                    capture_output=True, text=True, timeout=12, check=True)
                lines = result.stdout.splitlines()
                available = int(next(x for x in lines if x.startswith('MemAvailable:')).split()[1]) / 1024
                stats = json.loads(next(x for x in lines if x.startswith('{')))
                samples.append(dict(at=iso(time.time()), available_mb=round(available), **stats))
                if available < 300:
                    report['abort_reason'] = 'Server memory available below 300 MB'
                    stop.set()
            except Exception as error:
                report['abort_reason'] = 'Resource monitoring failed: ' + str(error)
                stop.set()
            if not samples:
                stop.set()
            finished.wait(3)

    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    while not samples and not stop.is_set():
        time.sleep(.1)
    stages = ([(1, True)] if args.warmup else []) + [(users, False) for users in args.users]
    for users, warming in stages:
        if stop.is_set():
            break
        started = time.monotonic()
        cpu_started = time.process_time()
        failures = []
        latencies = []
        sizes = []
        server_times = []
        cache_states = {}
        # Eight lanes per user, each reusing its HTTPS connection.
        def lane(user, index):
            connection = http.client.HTTPSConnection(endpoint.hostname, endpoint.port or 443,
                                                      timeout=45 if warming else args.timeout, context=tls_context)
            results = []
            try:
                for path in paths[index::8]:
                    if stop.is_set():
                        break
                    before = time.monotonic()
                    try:
                        connection.request('GET', path, headers={'User-Agent': 'OpenRain-bounded-load-test/1'})
                        response = connection.getresponse()
                        body = response.read()
                        valid = response.status == 200 and body[:4] == b'RIFF' and body[8:12] == b'WEBP'
                        timing = response.getheader('Server-Timing', '')
                        server_ms = None
                        if timing.startswith('radar;dur='):
                            try:
                                server_ms = float(timing.split('=', 1)[1])
                            except ValueError:
                                pass
                        results.append((time.monotonic() - before, len(body),
                                        None if valid else 'HTTP/image ' + str(response.status),
                                        server_ms, response.getheader('X-Radar-Cache', 'unknown')))
                        if not valid:
                            stop.set()
                    except Exception as error:
                        results.append((time.monotonic() - before, 0, str(error), None, 'no-response'))
                        stop.set()
            finally:
                connection.close()
            return results
        with concurrent.futures.ThreadPoolExecutor(max_workers=users * min(8, args.frames)) as pool:
            futures = [pool.submit(lane, user, i) for user in range(users) for i in range(min(8, args.frames))]
            for future in concurrent.futures.as_completed(futures):
                for elapsed, size, error, server_ms, cache_state in future.result():
                    latencies.append(elapsed)
                    sizes.append(size)
                    cache_states[cache_state] = cache_states.get(cache_state, 0) + 1
                    if server_ms is not None:
                        server_times.append(server_ms)
                    if error:
                        failures.append(error)
        duration = time.monotonic() - started
        ordered = sorted(latencies)
        percentile = lambda p: round(ordered[min(len(ordered)-1, math.ceil(len(ordered)*p)-1)], 3) if ordered else None
        stage = dict(users=users, requests=len(latencies), expected=users*args.frames,
                     errors=len(failures), error_examples=failures[:5], seconds=round(duration, 2),
                     requests_per_second=round(len(latencies)/duration, 1),
                     mib=round(sum(sizes)/1024**2, 2), p50_seconds=percentile(.5),
                     p95_seconds=percentile(.95), max_seconds=percentile(1))
        stage['cache_at_arrival'] = cache_states
        stage['warmup'] = warming
        stage['client_cpu_seconds'] = round(time.process_time() - cpu_started, 2)
        stage['server_timing_samples'] = len(server_times)
        stage['server_p95_ms'] = (sorted(server_times)[math.ceil(len(server_times)*.95)-1]
                                  if server_times else None)
        report['stages'].append(stage)
        print(json.dumps(stage), flush=True)
        if failures:
            report.setdefault('abort_reason', 'Request failure; higher stages skipped')
        if not stop.is_set():
            time.sleep(3)
    finished.set()
    watcher.join(timeout=15)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(str(args.output), flush=True)
    if stop.is_set():
        raise SystemExit(1)


if __name__ == '__main__':
    main()
