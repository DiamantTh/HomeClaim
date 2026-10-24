#!/usr/bin/env python3
"""Run a pinned, test-only protocol-777 client against a disposable Paper server."""

from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import shutil
import sqlite3
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


CLIENT_REPOSITORY = "https://github.com/ImWuMie/mc-proto-bot.git"
CLIENT_COMMIT = "71fce9b6f177f3392705f43ae51f7485bb5868d7"
PLAYER_A = "HomeClaimTestA"
PLAYER_B = "HomeClaimTestB"
ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "homeclaim-platform-paper" / "build"
SERVER_KIND = os.environ.get("HOMECLAIM_E2E_SERVER_KIND", "paper")
SCENARIO_MODE = os.environ.get("HOMECLAIM_E2E_MODE", "full")
RUN_DIR = BUILD / f"{SERVER_KIND}-networked-player-e2e"
REPORT_DIR = BUILD / "reports" / f"{SERVER_KIND}-networked-player-e2e"
SERVER_DIR = RUN_DIR / "server"
CLIENT_DIR = RUN_DIR / "protobot"
READY_FILE = SERVER_DIR / "plugins" / "HomeClaimWorldE2E" / "result.txt"
STATE_FILE = SERVER_DIR / "plugins" / "HomeClaimWorldE2E" / "network-player-state.txt"
CONTROL_DIR = SERVER_DIR / "plugins" / "HomeClaimWorldE2E" / "network-control"


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def run_checked(args: list[str], *, cwd: Path, env: dict[str, str], timeout: int = 180) -> None:
    subprocess.run(args, cwd=cwd, env=env, check=True, timeout=timeout)


def checkout_client() -> None:
    if CLIENT_DIR.exists():
        shutil.rmtree(CLIENT_DIR)
    CLIENT_DIR.mkdir(parents=True)
    env = os.environ.copy()
    env["GIT_CONFIG_GLOBAL"] = os.devnull
    run_checked(["git", "init", str(CLIENT_DIR)], cwd=RUN_DIR, env=env)
    run_checked(["git", "-C", str(CLIENT_DIR), "remote", "add", "origin", CLIENT_REPOSITORY], cwd=RUN_DIR, env=env)
    run_checked(["git", "-C", str(CLIENT_DIR), "fetch", "--depth=1", "origin", CLIENT_COMMIT], cwd=RUN_DIR, env=env)
    run_checked(["git", "-C", str(CLIENT_DIR), "checkout", "--detach", "FETCH_HEAD"], cwd=RUN_DIR, env=env)
    actual = subprocess.check_output(["git", "-C", str(CLIENT_DIR), "rev-parse", "HEAD"], text=True).strip()
    if actual != CLIENT_COMMIT:
        raise RuntimeError(f"Pinned Protocol client mismatch: expected {CLIENT_COMMIT}, got {actual}")
    run_checked([sys.executable, "-m", "unittest", "-v", "tests.test_protocol_777"], cwd=CLIENT_DIR, env=env, timeout=120)


def configure_server(paper_jar: Path, plugin_jar: Path, companion_jar: Path, port: int, web_port: int) -> None:
    if SERVER_DIR.exists():
        shutil.rmtree(SERVER_DIR)
    SERVER_DIR.mkdir(parents=True)
    shutil.copy2(paper_jar, SERVER_DIR / paper_jar.name)
    plugins = SERVER_DIR / "plugins"
    plugins.mkdir()
    shutil.copy2(plugin_jar, plugins / "HomeClaim.jar")
    shutil.copy2(companion_jar, plugins / companion_jar.name)
    (SERVER_DIR / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (SERVER_DIR / "ops.json").write_text("[]\n", encoding="utf-8")
    (SERVER_DIR / "server.properties").write_text(
        "level-name=world\n"
        "server-ip=127.0.0.1\n"
        f"server-port={port}\n"
        "online-mode=false\n"
        "white-list=false\n"
        "enforce-whitelist=false\n"
        "max-players=4\n"
        "spawn-protection=0\n"
        "view-distance=4\n"
        "simulation-distance=4\n"
        "enable-rcon=false\n",
        encoding="utf-8",
    )
    config = SERVER_DIR / "config" / "HomeClaim.toml"
    config.parent.mkdir(parents=True)
    config.write_text(
        '[homeclaim]\nlocale = "en"\n\n'
        '[homeclaim.storage]\ntype = "JDBC"\ndriver = "SQLITE"\n'
        'sqliteFile = "plugins/HomeClaim/homeclaim.db"\nencoding = "UTF-8"\nmaximumPoolSize = 5\n\n'
        '[homeclaim.rest]\nenabled = false\n\n'
        '[homeclaim.webux]\n'
        'enabled = true\n'
        f'port = {web_port}\n'
        'host = "127.0.0.1"\nallowRemote = false\nsecureCookies = false\n'
        'allowedHosts = []\nallowedOrigins = []\n\n'
        '[homeclaim.backup]\ncompression = "zstd"\n\n'
        '[homeclaim.migrations]\nenabled = true\nallowNonPostgres = true\n'
        'baselineOnMigrate = true\nlocations = ["classpath:db/migration/common"]\n',
        encoding="utf-8",
    )


def lines_of_log(log_path: Path, count: int = 120) -> str:
    if not log_path.exists():
        return "(no Paper log was written)"
    return "\n".join(log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-count:])


def start_server(paper_jar: Path, plugin_jar: Path, companion_jar: Path, java: str, log_path: Path) -> tuple[subprocess.Popen, int, int]:
    port, web_port = free_port(), free_port()
    configure_server(paper_jar, plugin_jar, companion_jar, port, web_port)
    log = log_path.open("w", encoding="utf-8")
    process = subprocess.Popen(
        [java, "-Dcom.mojang.eula.agree=true", "-Dhomeclaim.worlde2e.phase=network-player", "-jar", paper_jar.name, "nogui"],
        cwd=SERVER_DIR,
        stdout=log,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Paper exited before network fixtures were ready (exit {process.returncode})\n{lines_of_log(log_path)}")
        if READY_FILE.exists():
            ready = READY_FILE.read_text(encoding="utf-8", errors="replace")
            if ready.startswith("READY network-player-e2e"):
                return process, port, web_port
            if ready.startswith("FAIL"):
                raise RuntimeError(f"Paper network fixture failed: {ready}\n{lines_of_log(log_path)}")
        time.sleep(0.2)
    raise TimeoutError(f"Paper network fixtures did not become ready within 180 seconds\n{lines_of_log(log_path)}")


async def wait_ready_file(timeout: float = 60.0) -> dict[str, str]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if READY_FILE.exists():
            report = READY_FILE.read_text(encoding="utf-8", errors="replace")
            if report.startswith("FAIL"):
                raise AssertionError(report)
            if report.startswith("PASS"):
                raise AssertionError(f"Harness shut down before the driver completed: {report}")
        if STATE_FILE.exists():
            state = dict(line.split("=", 1) for line in STATE_FILE.read_text(encoding="utf-8").splitlines() if "=" in line)
            if "recoveryJob" in state:
                return state
        await asyncio.sleep(0.1)
    raise TimeoutError("Network fixture metadata did not appear")


class NetworkClient:
    def __init__(self, bot, plain_text):
        self.bot = bot
        self.plain_text = plain_text
        self.messages: asyncio.Queue[str] = asyncio.Queue()
        self.last_command_at = 0.0

        async def on_system_chat(component, overlay):
            try:
                self.messages.put_nowait(plain_text(component) + " | " + json.dumps(component, default=str))
            except Exception as error:  # diagnostic only; the packet still reached the client
                self.messages.put_nowait(str(component) + f" [component decode: {error}]")

        async def on_player_chat(sender, name, message, chat_type_id, target_name):
            try:
                self.messages.put_nowait(plain_text(message))
            except Exception as error:
                self.messages.put_nowait(str(message) + f" [component decode: {error}]")

        bot.on("system_chat", on_system_chat)
        bot.on("player_chat", on_player_chat)

    async def command(self, text: str) -> None:
        delay = 1.25 - (time.monotonic() - self.last_command_at)
        if delay > 0:
            await asyncio.sleep(delay)
        await asyncio.wait_for(self.bot.send_command(text), timeout=10)
        self.last_command_at = time.monotonic()

    async def next_message(self, predicate, timeout: float = 20.0) -> str:
        deadline = time.monotonic() + timeout
        seen: list[str] = []
        while time.monotonic() < deadline:
            remaining = max(0.01, deadline - time.monotonic())
            try:
                message = await asyncio.wait_for(self.messages.get(), timeout=remaining)
            except TimeoutError:
                break
            seen.append(message)
            if predicate(message):
                return message
        raise TimeoutError(f"Expected network chat message not received; observed={seen[-30:]}")

    async def close(self) -> None:
        await asyncio.wait_for(self.bot.close(), timeout=10)


async def connect_player(username: str, host: str, port: int) -> NetworkClient:
    sys.path.insert(0, str(CLIENT_DIR))
    from protobot import Bot, plain_text

    bot = Bot(host, port=port, username=username, version="26.3", resolve_srv=False, connect_timeout=20)
    client = NetworkClient(bot, plain_text)
    await asyncio.wait_for(bot.start(), timeout=20)
    await asyncio.wait_for(bot.wait_loaded(timeout=40), timeout=45)
    return client


async def control(sequence: int, operation: str, timeout: float = 60.0) -> str:
    CONTROL_DIR.mkdir(parents=True, exist_ok=True)
    request = CONTROL_DIR / "request.txt"
    response = CONTROL_DIR / "response.txt"
    request.write_text(f"{sequence}|{operation}\n", encoding="utf-8")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if READY_FILE.exists():
            report = READY_FILE.read_text(encoding="utf-8", errors="replace")
            if report.startswith("FAIL"):
                raise AssertionError(report)
            if report.startswith("PASS"):
                return report.strip()
        if response.exists():
            fields = response.read_text(encoding="utf-8", errors="replace").strip().split("|", 2)
            if len(fields) == 3 and int(fields[0]) == sequence:
                if fields[1] != "PASS":
                    raise AssertionError(fields[2])
                return fields[2]
        await asyncio.sleep(0.1)
    raise TimeoutError(f"Paper harness did not acknowledge control operation {operation}")


def post_json(url: str, origin: str, payload: dict, cookie: str | None = None, csrf: str | None = None):
    headers = {"Content-Type": "application/json", "Origin": origin}
    if cookie:
        headers["Cookie"] = cookie
    if csrf:
        headers["X-CSRF-Token"] = csrf
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
    try:
        return urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        return error


def get_json(url: str, cookie: str | None = None):
    headers = {"Accept": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    request = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(request, timeout=10)


def websocket_roundtrip(host: str, port: int, cookie: str, origin: str) -> None:
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.settimeout(10)
        request = (
            f"GET /ws HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {origin}\r\nCookie: {cookie}\r\n\r\n"
        )
        sock.sendall(request.encode("ascii"))
        headers = bytearray()
        while not headers.endswith(b"\r\n\r\n"):
            chunk = sock.recv(1)
            if not chunk:
                break
            headers.extend(chunk)
        if not headers.startswith(b"HTTP/1.1 101"):
            raise AssertionError(f"Authenticated WebSocket handshake rejected: {headers.decode(errors='replace')}")

        def send_text(text: str) -> None:
            payload = text.encode()
            mask = os.urandom(4)
            length = len(payload)
            header = bytearray([0x81])
            if length < 126:
                header.append(0x80 | length)
            elif length < 65536:
                header.append(0x80 | 126)
                header.extend(struct.pack("!H", length))
            else:
                header.append(0x80 | 127)
                header.extend(struct.pack("!Q", length))
            sock.sendall(bytes(header) + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(payload)))

        def read_frame() -> tuple[int, str]:
            def read_exact(size: int) -> bytes:
                data = bytearray()
                while len(data) < size:
                    chunk = sock.recv(size - len(data))
                    if not chunk:
                        raise ConnectionError("WebSocket closed during frame read")
                    data.extend(chunk)
                return bytes(data)

            first, second = read_exact(2)
            opcode = first & 0x0F
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", read_exact(8))[0]
            mask = read_exact(4) if second & 0x80 else b""
            payload = bytearray(read_exact(length))
            if mask:
                payload = bytearray(value ^ mask[index % 4] for index, value in enumerate(payload))
            return opcode, payload.decode("utf-8", errors="replace")

        send_text('{"type":"ping"}')
        opcode, reply = read_frame()
        if opcode != 1 or json.loads(reply).get("type") != "pong":
            raise AssertionError(f"WebSocket ping did not return pong: {opcode}, {reply}")
        send_text('{"type":"plot_command","command":"reset"}')
        opcode, reply = read_frame()
        if opcode != 1 or json.loads(reply).get("error") != "unsupported_message":
            raise AssertionError(f"WebSocket accepted an unsupported mutation request: {opcode}, {reply}")


def mutation_job_ids(operation: str, actor_id: str) -> set[str]:
    database = SERVER_DIR / "plugins" / "HomeClaim" / "homeclaim.db"
    with sqlite3.connect(f"file:{database}?mode=ro", uri=True, timeout=2) as connection:
        rows = connection.execute(
            "SELECT id, details FROM plot_mutation_jobs WHERE operation = ? ORDER BY created_at DESC LIMIT 100",
            (operation,),
        ).fetchall()
    return {job_id for job_id, details in rows if actor_id in (details or "")}


async def wait_mutation_job(operation: str, actor_id: str, previous_ids: set[str], timeout: float = 90.0) -> str:
    database = SERVER_DIR / "plugins" / "HomeClaim" / "homeclaim.db"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with sqlite3.connect(f"file:{database}?mode=ro", uri=True, timeout=2) as connection:
                rows = connection.execute(
                    "SELECT id, status, details FROM plot_mutation_jobs WHERE operation = ? ORDER BY created_at DESC LIMIT 100",
                    (operation,),
                ).fetchall()
            for job_id, status, details in rows:
                if job_id not in previous_ids and actor_id in (details or ""):
                    if status in ("FAILED", "RECOVERY_REQUIRED", "CANCELLED"):
                        raise AssertionError(f"Disconnected player's {operation} job {job_id} became {status}: {details}")
                    if status == "COMPLETED":
                        return job_id
        except sqlite3.OperationalError:
            pass
        await asyncio.sleep(0.25)
    raise TimeoutError(f"No new terminal COMPLETED {operation} job for player {actor_id} within {timeout}s")


async def run_scenarios(host: str, port: int, web_port: int, process: subprocess.Popen) -> None:
    state = await wait_ready_file()
    region_a, region_b, region_foreign = state["first"], state["second"], state["foreign"]
    backup_id, recovery_job = state["initialBackup"], state["recoveryJob"]
    first = await connect_player(PLAYER_A, host, port)
    second = await connect_player(PLAYER_B, host, port)
    player_a_id, player_b_id = str(first.bot.uuid), str(second.bot.uuid)
    if player_a_id != uuid_for_offline_name(PLAYER_A) or player_b_id != uuid_for_offline_name(PLAYER_B):
        raise AssertionError("Offline test player UUID did not match the actual network Player identity")

    await first.command("/plot info")
    await first.next_message(lambda message: region_a in message, 15)
    await first.next_message(lambda message: state["area"] in message, 15)
    await first.next_message(lambda message: "Status:" in message and "Claimed" in message, 15)

    await first.command(f"/plot copy {region_foreign}")
    await first.next_message(lambda message: "permission" in message.lower() or "denied" in message.lower(), 10)
    await control(0, "assert-foreign-denied")

    # A live unauthorized recovery command is rejected before looking up the job.
    await first.command(f"/plot jobs recover {recovery_job} restore-target")
    await first.next_message(lambda message: "permission" in message.lower() or "denied" in message.lower(), 10)
    await second.command(f"/plot jobs inspect {recovery_job}")
    await second.next_message(lambda message: "Status: RECOVERY_REQUIRED" in message, 10)
    while not second.messages.empty():
        second.messages.get_nowait()
    # /plot jobs inspect and recover share the production command rate-limit bucket.
    await asyncio.sleep(1.0)
    await second.command(f"/plot jobs recover {recovery_job} restore-target")
    recovery_request_messages = []
    request_deadline = time.monotonic() + 3
    while time.monotonic() < request_deadline:
        try:
            recovery_request_messages.append(await asyncio.wait_for(second.messages.get(), timeout=0.25))
        except TimeoutError:
            pass
    if not any("within 30 seconds" in message for message in recovery_request_messages):
        raise AssertionError(f"Authorized recovery did not create a visible confirmation: {recovery_request_messages}")
    await control(1, "assert-recovery-pending")
    await second.command("/plot confirm")
    recovery_result = await second.next_message(
        lambda message: any(text in message.lower() for text in (
            "recovery completed", "nothing to confirm", "recovery could not", "remains blocked", "permission denied"
        )), 45
    )
    if "Recovery completed" not in recovery_result:
        raise AssertionError(f"Admin confirmation did not complete recovery: {recovery_result}")
    await control(2, "assert-recovery")

    # Pending confirmations are actor-bound, survive a short disconnect for the same UUID,
    # and only reset after a real client command is confirmed.
    await first.command("/plot reset")
    await first.next_message(lambda message: "Confirm with /plot reset confirm" in message, 10)
    await control(3, "assert-reset-pending")
    await second.command("/plot confirm")
    await second.next_message(lambda message: "nothing to confirm" in message.lower(), 10)
    await first.close()
    first = await connect_player(PLAYER_A, host, port)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "eingereiht" in message.lower() or "reset job queued" in message.lower(), 10)
    await first.next_message(lambda message: "Plot reset completed" in message, 45)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "nothing to confirm" in message.lower(), 10)
    await control(4, "assert-reset")

    # Expiry is checked against the production 30 second TTL because the runtime command's
    # clock is not injectable. No operation is confirmed during the bounded TTL window.
    await first.command("/plot reset")
    await first.next_message(lambda message: "Confirm with /plot reset confirm" in message, 10)
    deadline = time.monotonic() + 31
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Paper stopped while waiting for confirmation expiry")
        await asyncio.sleep(min(0.5, deadline - time.monotonic()))
    await first.command("/plot confirm")
    await first.next_message(lambda message: "nothing to confirm" in message.lower(), 10)
    await control(5, "assert-reset")

    # A target signature change invalidates a previously issued transfer confirmation.
    await control(6, "seed-copy")
    await first.command(f"/plot copy {region_b}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 10)
    await control(7, "mutate-target-state")
    await first.command("/plot confirm")
    await first.next_message(lambda message: "changed" in message.lower(), 10)
    await control(8, "assert-unchanged")

    # Player B cannot consume A's confirmation; A then confirms the same operation.
    await first.command(f"/plot copy {region_b}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 10)
    await control(10, "assert-unchanged")
    await second.command("/plot confirm")
    await second.next_message(lambda message: "nothing to confirm" in message.lower(), 10)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "Transfer completed" in message, 45)
    await control(11, "assert-copy")

    await control(12, "seed-move")
    await first.command(f"/plot move {region_b}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 10)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "Transfer completed" in message, 45)
    await control(13, "assert-move")

    await control(14, "seed-swap")
    await first.command(f"/plot swap {region_b}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 10)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "Transfer completed" in message, 45)
    await control(15, "assert-swap")

    await first.command(f"/plot restore {backup_id}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 20)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "Restored" in message, 45)
    await control(16, "assert-restore")

    # Disconnect immediately after the command path confirms an asynchronous COPY. The
    # Companion compares the actual world after completion; the server job must outlive A.
    previous_copy_jobs = mutation_job_ids("COPY", player_a_id)
    await control(17, "seed-copy")
    await first.command(f"/plot copy {region_b}")
    await first.next_message(lambda message: "Confirm with /plot confirm" in message, 10)
    await first.command("/plot confirm")
    await first.next_message(lambda message: "Starting copy" in message, 10)
    await first.close()
    disconnected_copy_job = await wait_mutation_job("COPY", player_a_id, previous_copy_jobs)
    await control(18, "assert-copy", 90)
    first = await connect_player(PLAYER_A, host, port)

    # Exercise the real in-game one-time pairing with this connected Player UUID.
    await first.command("/homeclaim web login")
    code_message = await first.next_message(lambda message: "Web-Login-Code" in message, 10)
    # WebUxAuthStore issues 24 random bytes as unpadded URL-safe Base64; the
    # alphabet includes lowercase letters and underscores as well as '-' .
    code_match = re.search(r"Web-Login-Code.*?:\s*([A-Za-z0-9_-]{20,})", code_message)
    if not code_match:
        raise AssertionError(f"The live pairing command did not expose a parseable one-time code: {code_message}")
    code = code_match.group(1)
    origin = f"http://127.0.0.1:{web_port}"
    pair = post_json(f"{origin}/auth/pair", origin, {"code": code})
    if pair.status != 200:
        raise AssertionError(f"Pair exchange failed with HTTP {pair.status}: {pair.read().decode(errors='replace')}")
    pair_data = json.loads(pair.read())
    if pair_data.get("playerId") != player_a_id or pair_data.get("username") != PLAYER_A:
        raise AssertionError(f"Pair identity was not derived from the live Player: {pair_data}")
    cookie_header = pair.headers.get("Set-Cookie", "")
    if "HttpOnly" not in cookie_header or "SameSite=Lax" not in cookie_header or "Secure" in cookie_header:
        raise AssertionError(f"Loopback session cookie flags differed from the configured test policy: {cookie_header}")
    cookie = cookie_header.split(";", 1)[0]
    replay = post_json(f"{origin}/auth/pair", origin, {"code": code})
    if replay.status != 401:
        raise AssertionError(f"One-time pairing code replay was not rejected: HTTP {replay.status}")
    with get_json(f"{origin}/auth/session", cookie) as response:
        session = json.loads(response.read())
    if session.get("playerId") != player_a_id or session.get("username") != PLAYER_A:
        raise AssertionError(f"Browser session identity does not match the live player: {session}")
    csrf = session.get("csrfToken")
    if not csrf:
        raise AssertionError("Session response did not contain the CSRF value required for logout")
    websocket_roundtrip("127.0.0.1", web_port, cookie, origin)
    logout = post_json(f"{origin}/auth/logout", origin, {}, cookie, csrf)
    if logout.status != 200:
        raise AssertionError(f"Session logout failed: HTTP {logout.status}")
    try:
        get_json(f"{origin}/auth/session", cookie)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
    else:
        raise AssertionError("Logout left the browser session valid")

    await first.close()
    await second.close()
    result = await control(19, "finish")
    if not result.startswith("PASS"):
        raise AssertionError(result)


async def run_purpur_smoke(host: str, port: int) -> None:
    state = await wait_ready_file()
    first = await connect_player(PLAYER_A, host, port)
    if str(first.bot.uuid) != uuid_for_offline_name(PLAYER_A):
        raise AssertionError("Purpur test Player UUID did not match the real offline-mode Player identity")

    await first.command("/plot info")
    await first.next_message(lambda message: state["first"] in message, 15)
    second = await connect_player(PLAYER_B, host, port)
    if str(second.bot.uuid) != uuid_for_offline_name(PLAYER_B):
        raise AssertionError("Purpur admin test Player UUID did not match the real offline-mode Player identity")
    await second.command(f"/plot jobs inspect {state['recoveryJob']}")
    await second.next_message(lambda message: "Status: RECOVERY_REQUIRED" in message, 10)
    await asyncio.sleep(1.0)
    await second.command(f"/plot jobs recover {state['recoveryJob']} restore-target")
    await second.next_message(lambda message: "within 30 seconds" in message, 10)
    await second.command("/plot confirm")
    await second.next_message(lambda message: "Recovery completed" in message, 45)
    await control(0, "assert-recovery")
    await first.command("/plot reset")
    await first.next_message(lambda message: "Confirm with /plot reset confirm" in message, 10)
    await control(1, "assert-reset-pending")
    await first.command("/plot confirm")
    await first.next_message(lambda message: "reset job queued" in message.lower() or "eingereiht" in message.lower(), 10)
    await first.next_message(lambda message: "Plot reset completed" in message, 45)
    await control(2, "assert-reset")
    await first.close()
    await second.close()
    result = await control(3, "finish-smoke")
    if not result.startswith("PASS"):
        raise AssertionError(result)


def uuid_for_offline_name(name: str) -> str:
    import hashlib
    import uuid

    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: networked-player-e2e.py SERVER_JAR HOMECLaIM_JAR COMPANION_JAR", file=sys.stderr)
        return 2
    server_jar, plugin_jar, companion_jar = map(Path, sys.argv[1:])
    java = os.environ.get("HOMECLAIM_E2E_JAVA", "java")
    if RUN_DIR.exists():
        shutil.rmtree(RUN_DIR)
    RUN_DIR.mkdir(parents=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    log_path = REPORT_DIR / "paper.log"
    process: subprocess.Popen | None = None
    try:
        checkout_client()
        process, port, web_port = start_server(server_jar, plugin_jar, companion_jar, java, log_path)
        if SCENARIO_MODE == "full":
            asyncio.run(run_scenarios("127.0.0.1", port, web_port, process))
        elif SERVER_KIND == "purpur" and SCENARIO_MODE == "smoke":
            asyncio.run(run_purpur_smoke("127.0.0.1", port))
        else:
            raise ValueError(f"Unsupported network player test mode {SERVER_KIND}/{SCENARIO_MODE}")
        try:
            exit_code = process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.terminate()
            exit_code = process.wait(timeout=15)
            raise TimeoutError(f"Paper shutdown exceeded 90 seconds (exit after terminate {exit_code})")
        final = READY_FILE.read_text(encoding="utf-8", errors="replace") if READY_FILE.exists() else ""
        if exit_code != 0 or not final.startswith("PASS"):
            raise RuntimeError(f"Networked Player E2E failed: exit={exit_code}, report={final}\n{lines_of_log(log_path)}")
        if (SERVER_DIR / "ops.json").read_text(encoding="utf-8").strip() != "[]":
            raise AssertionError("The test granted operator status; explicit Paper permission attachment was expected")
        database = SERVER_DIR / "plugins" / "HomeClaim" / "homeclaim.db"
        with sqlite3.connect(database) as connection:
            expected_audit_actions = ({
                PLAYER_A: {
                    "homeclaim:region_reset",
                    "homeclaim:region_copy",
                    "homeclaim:region_move",
                    "homeclaim:region_swap",
                    "homeclaim:region_restore",
                },
                PLAYER_B: {"homeclaim:region_recovery"},
            } if SCENARIO_MODE == "full" else {PLAYER_A: {"homeclaim:region_reset"}})
            for player_name, expected_actions in expected_audit_actions.items():
                player_id = uuid_for_offline_name(player_name)
                rows = connection.execute(
                    "SELECT DISTINCT action_key FROM audit_log "
                    "WHERE actor_kind = 'PLAYER' AND actor_ref = ? AND status = 'COMPLETED'",
                    (player_id,),
                ).fetchall()
                completed_actions = {row[0] for row in rows}
                missing = expected_actions - completed_actions
                if missing:
                    raise AssertionError(
                        f"Completed player audit for {player_name} ({player_id}) missed actions {sorted(missing)}; "
                        f"observed={sorted(completed_actions)}"
                    )
        report = REPORT_DIR / "result.txt"
        report.write_text(final + f"auditActors={PLAYER_A}:{uuid_for_offline_name(PLAYER_A)},"
                          f"{PLAYER_B}:{uuid_for_offline_name(PLAYER_B)}\n", encoding="utf-8")
        report_log = REPORT_DIR / "paper.log"
        if log_path.resolve() != report_log.resolve():
            shutil.copy2(log_path, report_log)
        print(final.strip())
        print(f"Client: ProtoBot at pinned commit {CLIENT_COMMIT} (MIT), protocol 777; server={SERVER_KIND}")
        return 0
    except Exception as error:
        print(f"NETWORKED PLAYER E2E FAILED: {error}", file=sys.stderr)
        if log_path.exists():
            print(lines_of_log(log_path), file=sys.stderr)
        return 1
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        if RUN_DIR.exists():
            last_error = None
            for attempt in range(6):
                try:
                    shutil.rmtree(RUN_DIR)
                    last_error = None
                    break
                except OSError as error:
                    last_error = error
                    time.sleep(0.25 * (attempt + 1))
            if last_error is not None:
                raise last_error


if __name__ == "__main__":
    raise SystemExit(main())
