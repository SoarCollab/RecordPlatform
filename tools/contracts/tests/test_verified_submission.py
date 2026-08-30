"""Exercise safe capture, bounded helper IPC and official Console framing."""

from __future__ import annotations

import hashlib
import json
import os
import shlex
import signal
import struct
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from console_runtime import extract_runtime
from verified_submission import (
    MAX_ARTIFACT,
    capture_input,
    parse_result,
    read_safe,
    run_helper,
)


class RuntimeOutputTests(unittest.TestCase):
    """Keep framing compatibility separate from complete-bytecode trust."""

    def test_official_prompt_ansi_crlf_plain_and_json(self) -> None:
        """Official formatting preserves every byte without stripping metadata."""
        for value in (
            "0x6001\n",
            '"0x6001"\n',
            '{"result":"0x6001"}',
            "[group0]: /apps> 0x6001\r\n",
            "\x1b[32m[group0]: /apps> \x1b[0m0x6001\r\n[group0]: /apps> exit\r\n",
            "[group0]: /apps> getCode 0x" + "1" * 40 + "\n0x6001\n",
        ):
            with self.subTest(value=value):
                self.assertEqual(extract_runtime(value), "0x6001")

    def test_rejects_errors_invalid_and_ambiguous_code(self) -> None:
        """Mixed success/error, fragments, odd hex and duplicate results all fail closed."""
        for value in (
            "0x",
            "0x0",
            "0x123",
            "0x6001\n0x6001",
            "0x6001\n0x6002",
            "0x6001\nException: lookup failed",
            "[group0]: /apps> 0x6001junk",
            "random prefix 0x6001",
            "0x6001 0x6002",
            "[group0]: /apps> 0x",
            '{"result":"0x6001","result":"0x6001"}',
            '{"result":"0x6001","error":null}',
            "0x6001\r0x6002",
            '{"result":"0x6001"}\n{"result":null}',
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                extract_runtime(value)


class SubmissionTests(unittest.TestCase):
    """Verify the Python filesystem/IPC half independently of SDK transport fixtures."""

    def test_helper_packaging_recreates_classifier_and_uses_project_sdk(self) -> None:
        """Repeated package runs must not repackage stale helper classes or discover Console jars."""
        root = Path(__file__).resolve().parents[3]
        pom = ET.parse(root / "platform-fisco/pom.xml").getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        self.assertEqual(
            pom.findtext("m:properties/m:fisco.bcos.version", namespaces=ns), "3.8.0"
        )
        plugins = pom.findall("m:build/m:plugins/m:plugin", ns)
        ids = [plugin.findtext("m:artifactId", namespaces=ns) for plugin in plugins]
        self.assertLess(
            ids.index("maven-jar-plugin"), ids.index("spring-boot-maven-plugin")
        )
        jar = plugins[ids.index("maven-jar-plugin")]
        config = jar.find("m:executions/m:execution/m:configuration", ns)
        self.assertEqual(
            config.findtext("m:classifier", namespaces=ns), "deploy-helper"
        )
        self.assertEqual(config.findtext("m:forceCreation", namespaces=ns), "true")
        boot = plugins[ids.index("spring-boot-maven-plugin")]
        helper = boot.find("m:executions/m:execution/m:configuration", ns)
        self.assertEqual(
            helper.findtext("m:classifier", namespaces=ns), "deploy-helper"
        )
        self.assertEqual(
            helper.findtext("m:mainClass", namespaces=ns),
            "cn.flying.fisco_bcos.deploy.VerifiedContractSubmitter",
        )

    def test_safe_capture_rejects_redirected_nonregular_or_oversized_inputs(
        self,
    ) -> None:
        """No-follow and nonblocking descriptor checks reject symlinks, FIFO and huge files."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            actual = root / "data"
            actual.write_bytes(b"abc")
            self.assertEqual(read_safe(actual), b"abc")
            redirect = root / "redirect"
            redirect.symlink_to(actual)
            parent = root / "parent"
            parent.symlink_to(root, target_is_directory=True)
            fifo = root / "fifo"
            os.mkfifo(fifo)
            empty = root / "empty"
            empty.touch()
            huge = root / "huge"
            with huge.open("wb") as stream:
                stream.truncate(MAX_ARTIFACT + 1)
            for path in (redirect, parent / "data", fifo, empty, huge, root):
                with self.subTest(path=path), self.assertRaises((OSError, ValueError)):
                    read_safe(path)

    def test_replacement_after_open_cannot_change_captured_bytes(self) -> None:
        """An atomically replaced file path cannot redirect the already-open descriptor."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            target = root / "artifact"
            replacement = root / "replacement"
            target.write_bytes(b"verified input")
            replacement.write_bytes(b"decoy input")
            original_read = os.read

            def replace_then_read(fd: int, size: int) -> bytes:
                """Swap only once after the production opener has captured its file handle."""
                if replacement.exists():
                    replacement.replace(target)
                return original_read(fd, size)

            with mock.patch(
                "verified_submission.os.read", side_effect=replace_then_read
            ):
                self.assertEqual(read_safe(target), b"verified input")
            self.assertEqual(target.read_bytes(), b"decoy input")

    def test_captures_three_exact_frames_for_real_signed_artifacts(self) -> None:
        """The pipe contains original catalog/raw ABI/creation bytes without transformations."""
        root = Path(__file__).resolve().parents[3]
        catalog = (
            root / "platform-fisco/src/main/resources/contract-registry/artifacts.json"
        )
        entries = json.loads(catalog.read_bytes())["contracts"]
        for name in ("Storage", "Sharing"):
            entry = next(value for value in entries if value["contractName"] == name)
            for variant in ("ecc", "sm"):
                framed, abi_hash = capture_input(
                    root,
                    catalog,
                    name,
                    variant,
                    "sha256:" + hashlib.sha256(catalog.read_bytes()).hexdigest(),
                )
                frames = []
                for _ in range(3):
                    length = struct.unpack(">I", framed[:4])[0]
                    frames.append(framed[4 : 4 + length])
                    framed = framed[4 + length :]
                self.assertFalse(framed)
                self.assertEqual(frames[0], catalog.read_bytes())
                self.assertEqual(frames[1], (root / entry["abiPath"]).read_bytes())
                self.assertEqual(
                    abi_hash, "sha256:" + hashlib.sha256(frames[1]).hexdigest()
                )
                self.assertEqual(
                    frames[2],
                    (root / entry["creationBytecodePaths"][variant]).read_bytes(),
                )

    def test_post_validation_abi_fifo_or_symlink_exits_before_helper_start(
        self,
    ) -> None:
        """The real shell write boundary rejects post-validation FIFO/symlink replacement promptly."""
        project = Path(__file__).resolve().parents[3]
        script = (project / "scripts/contract-deploy.sh").read_text()
        start = script.index("deploy_contract() {")
        boundary = script[start : script.index('\nSTORAGE_ADDR=""', start)]
        catalog_bytes = (
            project
            / "platform-fisco/src/main/resources/contract-registry/artifacts.json"
        ).read_bytes()
        catalog_hash = "sha256:" + hashlib.sha256(catalog_bytes).hexdigest()
        entry = next(
            item
            for item in json.loads(catalog_bytes)["contracts"]
            if item["contractName"] == "Storage"
        )
        for mode in ("fifo", "symlink"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                catalog = root / "catalog.json"
                catalog.write_bytes(catalog_bytes)
                abi = root / entry["abiPath"]
                abi.parent.mkdir(parents=True)
                abi.write_bytes((project / entry["abiPath"]).read_bytes())
                retained = abi.with_suffix(".original")
                marker = root / "helper-started"
                jar = root / "helper.jar"
                jar.write_bytes(b"unused helper fixture")
                bin_dir = root / "bin"
                bin_dir.mkdir()
                java = bin_dir / "java"
                java.write_text(
                    f"#!{sys.executable}\nfrom pathlib import Path\nPath({str(marker)!r}).touch()\nraise SystemExit(99)\n"
                )
                java.chmod(0o700)
                replace = f"mv {shlex.quote(str(abi))} {shlex.quote(str(retained))}\n"
                replace += (
                    f"mkfifo {shlex.quote(str(abi))}"
                    if mode == "fifo"
                    else f"ln -s {shlex.quote(str(retained))} {shlex.quote(str(abi))}"
                )
                values = {
                    "PROJECT_ROOT": root,
                    "CATALOG_FILE": catalog,
                    "ABI_DEST_DIR": abi.parent,
                    "VERIFIED_CATALOG_SHA256": catalog_hash,
                    "SUBMISSION_TOOL": project
                    / "tools/contracts/verified_submission.py",
                    "DEPLOY_HELPER_JAR": jar,
                    "DRY_RUN": "false",
                    "FISCO_CRYPTO_VARIANT": "ecc",
                    "DEPLOYMENT_SIGNER": "",
                }
                harness = "set -euo pipefail\n" + "\n".join(
                    f"{key}={shlex.quote(str(value))}" for key, value in values.items()
                )
                harness += "\nverify_fisco_chain_identity() { return 0; }\nstage_verified_console_artifacts() { return 0; }\n"
                harness += "revalidate_contract_deploy_inputs() {\n" + replace + "\n}\n"
                harness += 'fail() { printf "%s\\n" "$1"; }\n' + boundary
                harness += "\ndeploy_contract Storage OUT_ADDRESS OUT_TX OUT_BLOCK unused unused unused\n"
                environment = os.environ.copy()
                environment["PATH"] = str(bin_dir) + os.pathsep + environment["PATH"]
                with subprocess.Popen(
                    ["bash", "-c", harness],
                    env=environment,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    start_new_session=True,
                ) as process:
                    try:
                        stdout, _ = process.communicate(timeout=3)
                    except subprocess.TimeoutExpired:
                        # Also reap a blocked command-substitution child when exercising a regression.
                        os.killpg(process.pid, signal.SIGKILL)
                        process.communicate()
                        self.fail(
                            "Post-validation artifact replacement blocked the deployment boundary"
                        )
                    self.assertNotEqual(process.returncode, 0)
                    self.assertIn("failed or is uncertain", stdout)
                self.assertFalse(
                    marker.exists(),
                    "Unsafe replacement must not initialize the SDK helper",
                )
                self.assertEqual(
                    retained.read_bytes(), (project / entry["abiPath"]).read_bytes()
                )

    def test_strict_result_schema(self) -> None:
        """Helper metadata cannot be stitched from logs, duplicate JSON or unknown fields."""
        document = dict(
            schemaVersion="record-platform-verified-submission.v1",
            contractName="Storage",
            variant="ecc",
            contractAddress="0x" + "1" * 40,
            transactionHash="0x" + "2" * 64,
            signerAddress="0x" + "3" * 40,
        )
        raw = json.dumps(document).encode()
        self.assertEqual(parse_result(raw, "Storage", "ecc"), document)
        for bad in (
            b"",
            raw + raw,
            b"log\n" + raw,
            raw.replace(b'"ecc"', b'"sm"'),
            raw[:-1] + b',"variant":"ecc"}',
            b"x" * 4097,
            json.dumps({**document, "secret": "never print this"}).encode(),
            json.dumps({**document, "signerAddress": "0x" + "0" * 40}).encode(),
        ):
            with self.subTest(bad=bad[:30]), self.assertRaises(ValueError):
                parse_result(bad, "Storage", "ecc")

    def test_runner_bounds_output_timeout_and_never_retries(self) -> None:
        """A timeout or malformed transport stops once and never returns secret stderr."""
        with tempfile.TemporaryDirectory() as directory:
            marker = str(Path(directory) / "submitted")
            code = (
                "import pathlib,time; pathlib.Path("
                + repr(marker)
                + ").write_text('once'); time.sleep(5)"
            )
            with self.assertRaises(TimeoutError):
                run_helper([sys.executable, "-c", code], timeout=0.2)
            self.assertEqual(Path(marker).read_text(), "once")
        with self.assertRaises(ValueError):
            run_helper([sys.executable, "-c", "print('x'*5000)"], timeout=2)
        self.assertEqual(
            run_helper(
                [
                    sys.executable,
                    "-c",
                    "import sys; print('secret',file=sys.stderr); print('safe')",
                ]
            ),
            b"safe\n",
        )

    def test_runner_delivers_large_snapshot_without_pipe_deadlock(self) -> None:
        """Private stdin streams preserve bytes and avoid bidirectional pipe deadlocks."""
        captured = b"capture" * 200000
        code = "import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
        output = run_helper([sys.executable, "-c", code], captured=captured)
        self.assertEqual(output.strip().decode(), hashlib.sha256(captured).hexdigest())


if __name__ == "__main__":
    unittest.main()
