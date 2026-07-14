"""受指纹保护的合约部署脚本端到端 fixture 测试。"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import socket
import stat
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEPLOY_SCRIPT = PROJECT_ROOT / "scripts/contract-deploy.sh"
ENV_CHECK_SCRIPT = PROJECT_ROOT / "scripts/env-check.sh"
STORAGE_ADDRESS = "0x" + "1" * 40
SHARING_ADDRESS = "0x" + "2" * 40
STORAGE_TRANSACTION = "0x" + "a" * 64
SHARING_TRANSACTION = "0x" + "b" * 64
FIXED_EFFECTIVE_AT = "2026-07-14T01:02:03Z"
CATALOG_PATH = (
    PROJECT_ROOT / "platform-fisco/src/main/resources/contract-registry/artifacts.json"
)
ACTIVE_IDENTITIES = {
    entry["contractName"]: entry["semanticVersion"]
    for entry in json.loads(CATALOG_PATH.read_text(encoding="utf-8"))["contracts"]
    if entry["status"] == "ACTIVE"
}


class ContractDeployScriptTest(unittest.TestCase):
    """覆盖 dry-run、成功、artifact 漂移和链验证失败语义。"""

    def test_dry_run_does_not_modify_env_or_console(self) -> None:
        """dry-run 仍校验 catalog，但不得复制、编译、链写或更新环境文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = self._create_fixture(Path(temporary_directory), port=1)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(fixture, "--dry-run")

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse((fixture["console_dir"] / "contracts").exists())
            self.assertFalse(fixture["call_log"].exists())
            self.assertFalse(fixture["compiler_log"].exists())
            self.assertFalse(fixture["receipt_dir"].exists())
            self.assertFalse(fixture["date_log"].exists())
            self.assertIn("Signed contract artifact catalog verified", result.stdout)
            self.assertIn("DRY-RUN (no changes made)", result.stdout)

    def test_success_verifies_chain_before_atomic_activation(self) -> None:
        """正常路径必须取得 code/只读调用证据后一次性写回两个合约元数据。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            signed_abi = (
                PROJECT_ROOT / "platform-fisco/src/main/resources/abi/Sharing.abi"
            ).read_bytes()

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            env_text = fixture["env_file"].read_text(encoding="utf-8")
            expected_lines = {
                f"FISCO_STORAGE_CONTRACT={STORAGE_ADDRESS}",
                f"FISCO_SHARING_CONTRACT={SHARING_ADDRESS}",
                f"FISCO_STORAGE_DEPLOYMENT_TX={STORAGE_TRANSACTION}",
                f"FISCO_SHARING_DEPLOYMENT_TX={SHARING_TRANSACTION}",
                "FISCO_STORAGE_DEPLOYMENT_BLOCK=16",
                "FISCO_SHARING_DEPLOYMENT_BLOCK=17",
                f"FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT={FIXED_EFFECTIVE_AT}",
                f"FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT={FIXED_EFFECTIVE_AT}",
            }
            self.assertTrue(expected_lines.issubset(set(env_text.splitlines())))
            for key in (
                "FISCO_STORAGE_CONTRACT",
                "FISCO_SHARING_CONTRACT",
                "FISCO_STORAGE_DEPLOYMENT_TX",
                "FISCO_SHARING_DEPLOYMENT_TX",
                "FISCO_STORAGE_DEPLOYMENT_BLOCK",
                "FISCO_SHARING_DEPLOYMENT_BLOCK",
                "FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT",
                "FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT",
            ):
                self.assertEqual(env_text.count(f"{key}="), 1)

            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertIn("deploy Storage", calls)
            self.assertIn("deploy Sharing", calls)
            self.assertIn(f"getTransactionReceipt {STORAGE_TRANSACTION}", calls)
            self.assertIn(f"getTransactionReceipt {SHARING_TRANSACTION}", calls)
            self.assertIn(f"getCode {STORAGE_ADDRESS}", calls)
            self.assertIn(f"getCode {SHARING_ADDRESS}", calls)
            self.assertIn(
                f"call Storage {STORAGE_ADDRESS} contractIdentity",
                calls,
            )
            self.assertIn(
                f"call Sharing {SHARING_ADDRESS} contractIdentity",
                calls,
            )
            self.assertNotIn("getUserFiles", calls)
            self.assertNotIn("getShareInfo", calls)
            self.assertLess(calls.index("getGroupInfo"), calls.index("deploy Storage"))
            compiler_calls = fixture["compiler_log"].read_text(encoding="utf-8")
            self.assertEqual(compiler_calls.count("-v 0.8.35"), 2)

            receipt_files = list(fixture["receipt_dir"].glob("*.json"))
            self.assertEqual(len(receipt_files), 1)
            receipt_text = receipt_files[0].read_text(encoding="utf-8")
            receipt = json.loads(receipt_text)
            self.assertEqual(
                set(receipt),
                {
                    "schemaVersion",
                    "verificationStatus",
                    "catalogSha256",
                    "chainType",
                    "chainId",
                    "groupId",
                    "effectiveAt",
                    "contracts",
                },
            )
            self.assertNotIn("127.0.0.1", receipt_text)
            self.assertNotIn("FISCO_PRIVATE_KEY", receipt_text)
            self.assertNotIn("BLOCKCHAIN_RPC_TOKEN", receipt_text)
            self.assertEqual(
                receipt["schemaVersion"],
                "record-platform-contract-deployment-receipt.v1",
            )
            self.assertEqual(receipt["verificationStatus"], "VERIFIED")
            self.assertEqual(receipt["chainType"], "LOCAL_FISCO")
            self.assertEqual(receipt["chainId"], "chain0")
            self.assertEqual(receipt["groupId"], "group0")
            self.assertEqual(receipt["effectiveAt"], FIXED_EFFECTIVE_AT)
            self.assertEqual(
                receipt["catalogSha256"],
                "sha256:" + hashlib.sha256(CATALOG_PATH.read_bytes()).hexdigest(),
            )
            contracts = {
                contract["contractName"]: contract
                for contract in receipt["contracts"]
            }
            self.assertEqual(set(contracts), {"Storage", "Sharing"})
            self.assertEqual(
                contracts["Storage"],
                {
                    "contractName": "Storage",
                    "semanticVersion": ACTIVE_IDENTITIES["Storage"],
                    "address": STORAGE_ADDRESS,
                    "transactionHash": STORAGE_TRANSACTION,
                    "blockNumber": 16,
                    "effectiveAt": FIXED_EFFECTIVE_AT,
                },
            )
            self.assertEqual(
                contracts["Sharing"],
                {
                    "contractName": "Sharing",
                    "semanticVersion": ACTIVE_IDENTITIES["Sharing"],
                    "address": SHARING_ADDRESS,
                    "transactionHash": SHARING_TRANSACTION,
                    "blockNumber": 17,
                    "effectiveAt": FIXED_EFFECTIVE_AT,
                },
            )
            self.assertEqual(
                stat.S_IMODE(receipt_files[0].stat().st_mode),
                0o600,
            )
            self.assertEqual(
                fixture["date_log"].read_text(encoding="utf-8").splitlines(),
                ["date"],
            )
            self.assertEqual(
                (PROJECT_ROOT / "platform-fisco/src/main/resources/abi/Sharing.abi")
                .read_bytes(),
                signed_abi,
            )

    def test_abi_drift_blocks_all_deployment_and_activation(self) -> None:
        """编译 ABI 漂移必须在第一笔部署交易之前失败并保留旧配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_ABI_DRIFT": "Sharing"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("compiled ABI does not match", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertNotIn("deploy ", calls)

    def test_chain_mismatch_blocks_compile_deployment_and_activation(self) -> None:
        """节点 chainID 与显式配置不一致时必须在编译和链写前失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_CHAIN_ID": "chain-other"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FISCO chain mismatch", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertNotIn("deploy ", calls)
            self.assertFalse((fixture["console_dir"] / "contracts").exists())

    def test_missing_chain_id_blocks_console_and_deployment(self) -> None:
        """缺少显式 FISCO_CHAIN_ID 时不得查询 Console、编译或部署。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            env_text = fixture["env_file"].read_text(encoding="utf-8")
            fixture["env_file"].write_text(
                env_text.replace("FISCO_CHAIN_ID=chain0\n", ""),
                encoding="utf-8",
            )
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "FISCO_CHAIN_ID must be explicitly configured",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["call_log"].exists())
            self.assertFalse((fixture["console_dir"] / "contracts").exists())

    def test_non_local_active_chain_is_rejected_before_console(self) -> None:
        """本地 Console 部署脚本不得误用于 BSN FISCO/Besu 激活配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"BLOCKCHAIN_ACTIVE": "bsn-fisco"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "supports only BLOCKCHAIN_ACTIVE=local-fisco",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["call_log"].exists())

    def test_group_mismatch_blocks_compile_deployment_and_activation(self) -> None:
        """节点 groupID 与显式配置不一致时必须在编译和链写前失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_GROUP_ID": "group-other"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FISCO group mismatch", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertNotIn("deploy ", calls)

    def test_malformed_group_info_blocks_deployment_and_activation(self) -> None:
        """getGroupInfo 无法严格解析时必须失败并保留旧配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_GROUP_INFO_MALFORMED": "1"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "did not return one valid chainID/groupID pair",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertNotIn("deploy ", calls)

    def test_empty_runtime_code_blocks_activation(self) -> None:
        """部署后任一地址无 runtime code 时必须失败且不得激活半成品配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_EMPTY_CODE_ADDRESS": SHARING_ADDRESS},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("has no verifiable runtime code", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("deploy Storage", calls)
            self.assertIn("deploy Sharing", calls)
            self.assertIn(f"getCode {SHARING_ADDRESS}", calls)

    def test_duplicate_contract_addresses_block_activation(self) -> None:
        """继承合约只读调用兼容时，重复地址仍必须在激活前被显式拒绝。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_DUPLICATE_ADDRESS": "1"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("same contract address", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("deploy Storage", calls)
            self.assertIn("deploy Sharing", calls)
            self.assertNotIn("getCode", calls)

    def test_contract_identity_mismatch_blocks_receipt_and_activation(self) -> None:
        """链上合约名称或版本与 ACTIVE catalog 不一致时必须 fail closed。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_IDENTITY_MISMATCH": "Sharing"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Sharing identity mismatch", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn(
                f"call Sharing {SHARING_ADDRESS} contractIdentity",
                calls,
            )

    def test_contract_identity_nonzero_return_blocks_receipt_and_activation(self) -> None:
        """contractIdentity 返回非零状态或 revert 时不得发布回执或激活配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_IDENTITY_NONZERO": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "Storage.contractIdentity did not return one parseable successful identity",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_write_failure_blocks_env_activation(self) -> None:
        """审计回执无法落盘时，即使链上验证成功也不得更新 .env。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()
            invalid_receipt_target = fixture["root"] / "receipt-target-is-a-file"
            invalid_receipt_target.write_text("blocked\n", encoding="utf-8")

            result = self._run_script(
                fixture,
                "--receipt-dir",
                str(invalid_receipt_target),
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "receipt target must be a non-symlink directory",
                (result.stdout + result.stderr).lower(),
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertEqual(invalid_receipt_target.read_text(encoding="utf-8"), "blocked\n")

    def test_skip_verify_option_is_rejected(self) -> None:
        """部署后验证不得再由参数绕过。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = self._create_fixture(Path(temporary_directory), port=1)

            result = self._run_script(fixture, "--skip-verify")

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("no longer supported", result.stdout + result.stderr)
            self.assertFalse(fixture["call_log"].exists())

    def _create_fixture(self, root: Path, port: int) -> dict[str, Path]:
        """创建带假编译器、假 Console、假 timeout 和独立 .env 的部署环境。"""
        console_dir = root / "console"
        bin_dir = root / "bin"
        console_dir.mkdir(parents=True)
        bin_dir.mkdir(parents=True)
        call_log = root / "console-calls.log"
        compiler_log = root / "compiler-calls.log"
        date_log = root / "date-calls.log"
        receipt_dir = root / "receipts"
        env_file = root / ".env"
        env_file.write_text(
            "# deployment fixture\n"
            f"FISCO_PEER_ADDRESS=127.0.0.1:{port}\n"
            "FISCO_CHAIN_ID=chain0\n"
            "FISCO_GROUP_ID=group0\n"
            "FISCO_STORAGE_CONTRACT=0x3333333333333333333333333333333333333333\n"
            "FISCO_SHARING_CONTRACT=0x4444444444444444444444444444444444444444\n"
            f"CONTRACT_DEPLOYMENT_RECEIPT_DIR={receipt_dir}\n"
            "UNRELATED_SETTING=preserved\n",
            encoding="utf-8",
        )

        self._write_executable(
            console_dir / "contract2java.sh",
            """#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_COMPILER_LOG"
source_path=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s) source_path="$2"; shift 2 ;;
        *) shift ;;
    esac
done
name="$(basename "$source_path" .sol)"
mkdir -p contracts/sdk/abi contracts/sdk/bin
cp "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/abi/$name.abi" \
    "contracts/sdk/abi/$name.abi"
cp "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/bin/ecc/$name.bin" \
    "contracts/sdk/bin/$name.bin"
if [[ "${FAKE_ABI_DRIFT:-}" == "$name" ]]; then
    printf '[{"type":"function","name":"drifted","inputs":[]}]\n' \
        > "contracts/sdk/abi/$name.abi"
fi
""",
        )
        self._write_executable(
            console_dir / "console.sh",
            f"""#!/usr/bin/env bash
set -euo pipefail
input="$(cat)"
printf '%s\n---\n' "$input" >> "$FAKE_CALL_LOG"
case "$input" in
    *"getGroupInfo"*)
        if [[ "${{FAKE_GROUP_INFO_MALFORMED:-}}" == "1" ]]; then
            printf '{{"chainID":"chain0"\n'
        else
            printf '{{"chainID":"%s","groupID":"%s","nodeList":[]}}\n' \
                "${{FAKE_CHAIN_ID:-chain0}}" "${{FAKE_GROUP_ID:-group0}}"
        fi
        ;;
    *"deploy Storage"*)
        printf 'transaction hash: {STORAGE_TRANSACTION}\n'
        printf 'contract address: {STORAGE_ADDRESS}\n'
        ;;
    *"deploy Sharing"*)
        printf 'transaction hash: {SHARING_TRANSACTION}\n'
        if [[ "${{FAKE_DUPLICATE_ADDRESS:-}}" == "1" ]]; then
            printf 'contract address: {STORAGE_ADDRESS}\n'
        else
            printf 'contract address: {SHARING_ADDRESS}\n'
        fi
        ;;
    *"getTransactionReceipt {STORAGE_TRANSACTION}"*)
        printf '{{"blockNumber":"0x10"}}\n'
        ;;
    *"getTransactionReceipt {SHARING_TRANSACTION}"*)
        printf '{{"blockNumber":"0x11"}}\n'
        ;;
    *"getCode "*)
        if [[ -n "${{FAKE_EMPTY_CODE_ADDRESS:-}}" \
            && "$input" == *"$FAKE_EMPTY_CODE_ADDRESS"* ]]; then
            printf '0x\n'
        else
            printf '0x60006000\n'
        fi
        ;;
    *"call Storage"*"contractIdentity"*)
        if [[ "${{FAKE_IDENTITY_NONZERO:-}}" == "Storage" ]]; then
            printf 'Return code: 1\ndescription: execution reverted\n'
        elif [[ "${{FAKE_IDENTITY_MISMATCH:-}}" == "Storage" ]]; then
            printf 'Return code: 0\nReturn values:(WrongStorage, {ACTIVE_IDENTITIES["Storage"]})\n'
        else
            printf 'Return code: 0\nReturn values:(Storage, {ACTIVE_IDENTITIES["Storage"]})\n'
        fi
        ;;
    *"call Sharing"*"contractIdentity"*)
        if [[ "${{FAKE_IDENTITY_NONZERO:-}}" == "Sharing" ]]; then
            printf 'Return code: 1\ndescription: execution reverted\n'
        elif [[ "${{FAKE_IDENTITY_MISMATCH:-}}" == "Sharing" ]]; then
            printf 'Return code: 0\nReturn values:(WrongSharing, {ACTIVE_IDENTITIES["Sharing"]})\n'
        else
            printf 'Return code: 0\nReturn values:(Sharing, {ACTIVE_IDENTITIES["Sharing"]})\n'
        fi
        ;;
    *)
        printf 'unsupported fake console command\n' >&2
        exit 2
        ;;
esac
""",
        )
        self._write_executable(
            bin_dir / "timeout",
            """#!/usr/bin/env bash
set -euo pipefail
shift
exec "$@"
""",
        )
        self._write_executable(
            bin_dir / "date",
            f"""#!/usr/bin/env bash
set -euo pipefail
printf 'date\n' >> "$FAKE_DATE_LOG"
printf '{FIXED_EFFECTIVE_AT}\n'
""",
        )
        return {
            "root": root,
            "console_dir": console_dir,
            "bin_dir": bin_dir,
            "call_log": call_log,
            "compiler_log": compiler_log,
            "date_log": date_log,
            "receipt_dir": receipt_dir,
            "env_file": env_file,
        }

    def _run_script(
        self,
        fixture: dict[str, Path],
        *arguments: str,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """在隔离 fixture 中执行真实部署脚本并返回完整输出。"""
        environment = os.environ.copy()
        environment.update({
            "NO_COLOR": "1",
            "FAKE_PROJECT_ROOT": str(PROJECT_ROOT),
            "FAKE_CALL_LOG": str(fixture["call_log"]),
            "FAKE_COMPILER_LOG": str(fixture["compiler_log"]),
            "FAKE_DATE_LOG": str(fixture["date_log"]),
            "PATH": f"{fixture['bin_dir']}{os.pathsep}{environment['PATH']}",
        })
        if extra_env:
            environment.update(extra_env)
        command = [
            "bash",
            str(DEPLOY_SCRIPT),
            "--console-dir",
            str(fixture["console_dir"]),
            "--env-file",
            str(fixture["env_file"]),
            *arguments,
        ]
        return subprocess.run(
            command,
            cwd=PROJECT_ROOT,
            env=environment,
            text=True,
            capture_output=True,
            timeout=60,
            check=False,
        )

    def _start_tcp_probe(self) -> tuple[threading.Thread, int]:
        """启动一次性本地 TCP listener，供脚本真实执行连通性探测。"""
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]

        def accept_once() -> None:
            """接受并关闭一次探测连接。"""
            try:
                connection, _ = server.accept()
                connection.close()
            finally:
                server.close()

        thread = threading.Thread(target=accept_once, daemon=True)
        thread.start()
        return thread, port

    def _write_executable(self, path: Path, content: str) -> None:
        """写入 UTF-8 shell fixture，并授予仅测试用户可执行权限。"""
        path.write_text(content, encoding="utf-8")
        path.chmod(0o700)


class EnvironmentContractCheckTest(unittest.TestCase):
    """覆盖 env-check 对部署证据三元组的静态校验。"""

    def test_complete_deployment_triplets_pass(self) -> None:
        """两个合约证据均完整时，contracts 环境检查必须通过。"""
        result = self._run_contract_check()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            result.stdout.count("complete tx/block/effectiveAt triplet"),
            2,
        )

    def test_partial_deployment_triplet_fails(self) -> None:
        """任一合约仅配置 tx/block 时必须失败，禁止伪造完整部署证据。"""
        result = self._run_contract_check(
            {"FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT": ""},
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT together",
            result.stdout + result.stderr,
        )

    def _run_contract_check(
        self,
        overrides: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """在无项目 .env 的临时根目录中执行 contracts 子检查。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            project_root = Path(temporary_directory)
            script_dir = project_root / "scripts"
            script_dir.mkdir()
            script_path = script_dir / "env-check.sh"
            shutil.copy2(ENV_CHECK_SCRIPT, script_path)
            environment = os.environ.copy()
            environment.update({
                "NO_COLOR": "1",
                "FISCO_STORAGE_CONTRACT": STORAGE_ADDRESS,
                "FISCO_SHARING_CONTRACT": SHARING_ADDRESS,
                "FISCO_STORAGE_DEPLOYMENT_TX": STORAGE_TRANSACTION,
                "FISCO_SHARING_DEPLOYMENT_TX": SHARING_TRANSACTION,
                "FISCO_STORAGE_DEPLOYMENT_BLOCK": "16",
                "FISCO_SHARING_DEPLOYMENT_BLOCK": "17",
                "FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT": FIXED_EFFECTIVE_AT,
                "FISCO_SHARING_DEPLOYMENT_EFFECTIVE_AT": FIXED_EFFECTIVE_AT,
            })
            if overrides:
                environment.update(overrides)
            return subprocess.run(
                ["bash", str(script_path), "--service", "contracts"],
                cwd=project_root,
                env=environment,
                text=True,
                capture_output=True,
                timeout=15,
                check=False,
            )


if __name__ == "__main__":
    unittest.main()
