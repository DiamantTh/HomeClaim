#!/usr/bin/env python3
"""Test-only custom biome registry removal/return across three real server processes."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import uuid


def main() -> None:
    server_jar, plugin_jar, companion_jar, fixture = map(lambda value: Path(value).resolve(), sys.argv[1:])
    root = Path(__file__).resolve().parent.parent
    server = root / 'homeclaim-platform-paper/build/biome-registry-restart/server'
    report = root / 'homeclaim-platform-paper/build/reports/biome-registry-restart'
    shutil.rmtree(server, ignore_errors=True)
    shutil.rmtree(report, ignore_errors=True)
    (server / 'plugins').mkdir(parents=True)
    report.mkdir(parents=True)
    shutil.copy2(server_jar, server / 'server.jar')
    shutil.copy2(plugin_jar, server / 'plugins/HomeClaim.jar')
    shutil.copy2(companion_jar, server / 'plugins' / companion_jar.name)
    (server / 'eula.txt').write_text('eula=true\n')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    (server / 'server.properties').write_text(
        f'level-name=world\nserver-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\n'
        'max-players=1\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nenable-rcon=false\n')
    run_id = str(uuid.uuid4())
    digest = hashlib.sha256(plugin_jar.read_bytes()).hexdigest()
    java = os.environ['HOMECLAIM_E2E_JAVA']
    results = []
    pack = server / 'world/datapacks/homeclaim-e2e'
    for phase in ('custom-biome-prepare', 'custom-biome-missing', 'custom-biome-restore'):
        if phase == 'custom-biome-missing':
            shutil.rmtree(pack)
        else:
            shutil.copytree(fixture, pack, dirs_exist_ok=True)
        result_file = server / 'plugins/HomeClaimWorldE2E/result.txt'
        json_file = result_file.with_name('e2e-result.json')
        result_file.unlink(missing_ok=True)
        json_file.unlink(missing_ok=True)
        command = [java, '-Xms256m', '-Xmx2g', '-Dcom.mojang.eula.agree=true',
                   f'-Dhomeclaim.worlde2e.phase={phase}', f'-Dhomeclaim.worlde2e.runId={run_id}',
                   f'-Dhomeclaim.worlde2e.pluginSha256={digest}', '-jar', 'server.jar', 'nogui']
        with (report / f'{phase}.log').open('w') as output:
            process = subprocess.Popen(command, cwd=server, stdout=output, stderr=subprocess.STDOUT)
            try:
                status = process.wait(timeout=300)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError(f'{phase} timed out; see {report}')
        message = result_file.read_text() if result_file.exists() else 'MISSING RESULT'
        assert status == 0, (phase, status, message)
        assert message.startswith('PREPARED' if phase.endswith('prepare') else 'PASS'), (phase, message)
        if not phase.endswith('prepare'):
            result = json.loads(json_file.read_text())
            assert result['runId'] == run_id and result['pluginSha256'] == digest
            assert result['overallStatus'] == 'PASSED'
            required = 'custom-biome-missing-after-restart' if phase.endswith('missing') else 'biome-restart-recovery'
            assert any(s['name'] == required and s['status'] == 'PASSED' for s in result['scenarios'])
            shutil.copy2(json_file, report / f'{phase}.json')
        results.append({'phase': phase, 'status': 'PASSED', 'diagnostic': message.strip()})
    (report / 'result.json').write_text(json.dumps({'runId': run_id, 'pluginSha256': digest,
        'overallStatus': 'PASSED', 'scenarios': results}, indent=2) + '\n')
    print('PASS custom biome registry present -> missing/rejected with blocker -> returned/exact recovery')
    shutil.rmtree(server)


if __name__ == '__main__':
    main()
