"""受指纹保护的合约部署脚本端到端 fixture 测试。"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import socket
import stat
import subprocess
import sys
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
            self.assertFalse((fixture["console_dir"] / "contract2java.sh").exists())
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
            launcher_calls = fixture["launcher_log"].read_text(encoding="utf-8")
            self.assertNotIn("console.sh", launcher_calls)
            self.assertGreaterEqual(launcher_calls.count("start.sh"), 9)
            self.assertIn("getGroupInfo", calls)
            self.assertIn("deploy Storage", calls)
            self.assertIn("deploy Sharing", calls)
            self.assertIn(f"getTransactionReceipt {STORAGE_TRANSACTION}", calls)
            self.assertIn(f"getTransactionReceipt {SHARING_TRANSACTION}", calls)
            self.assertIn(
                f"getGroupInfo\ngetTransactionReceipt {STORAGE_TRANSACTION}",
                calls,
            )
            self.assertIn(
                f"getGroupInfo\ngetTransactionReceipt {SHARING_TRANSACTION}",
                calls,
            )
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
            self.assertFalse(fixture["compiler_log"].exists())
            artifact_compiler_calls = fixture["artifact_compiler_log"].read_text(
                encoding="utf-8",
            )
            self.assertIn("ecc", artifact_compiler_calls)
            self.assertIn("sm", artifact_compiler_calls)
            self.assertEqual(artifact_compiler_calls.count("--bin-runtime"), 2)

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
                "record-platform-contract-deployment-receipt.v2",
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
                    "receiptStatus": "SUCCESS",
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
                    "receiptStatus": "SUCCESS",
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
            self._assert_no_staged_source_residue(fixture)
            self._assert_no_reproducible_build_residue(fixture)

    def test_failed_receipt_status_blocks_verification_and_activation(self) -> None:
        """部署交易回执状态非零时不得继续 runtime/identity 或发布证据。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_STORAGE_RECEIPT_STATUS": "0x10"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "receipt was missing, failed, ambiguous or mismatched",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())
            self.assertNotIn("getCode", fixture["call_log"].read_text(encoding="utf-8"))

    def test_missing_receipt_blocks_verification_and_activation(self) -> None:
        """RPC 返回 null 表示回执尚不存在时必须失败关闭而非补默认字段。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RECEIPT_MISSING": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_partial_receipt_shape_blocks_activation(self) -> None:
        """缺少 contractAddress 的部分 JSON 回执不得与部署文本拼接。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RECEIPT_MALFORMED": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_transaction_hash_mismatch_blocks_activation(self) -> None:
        """查询 tx 与回执内部 transactionHash 不一致时不得使用任一侧字段。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_STORAGE_RECEIPT_TRANSACTION": SHARING_TRANSACTION},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_contract_address_mismatch_blocks_activation(self) -> None:
        """部署输出地址与成功回执 contractAddress 不一致时必须拒绝。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_STORAGE_RECEIPT_ADDRESS": SHARING_ADDRESS},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_ambiguous_receipt_block_number_blocks_activation(self) -> None:
        """同一 RPC 输出出现两个不同区块候选时不得选择首个值。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RECEIPT_AMBIGUOUS": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_block_number_overflow_blocks_activation(self) -> None:
        """超出 registry Long 字段的区块号不得生成无法启动的配置。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_STORAGE_RECEIPT_BLOCK": str(2**63)},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_json_rpc_error_blocks_activation(self) -> None:
        """结构化 JSON-RPC error 不能被同一输出中的其他文本掩盖。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RECEIPT_JSON_RPC_ERROR": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

    def test_receipt_chain_context_mismatch_blocks_activation(self) -> None:
        """回执查询与 getGroupInfo 同一会话返回错误 chain 时必须失败关闭。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RECEIPT_CHAIN_ID": "chain1"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("receipt came from an unexpected chain/group", result.stdout)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            self.assertFalse(fixture["receipt_dir"].exists())

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

    def test_compiled_runtime_drift_blocks_first_deployment(self) -> None:
        """新编译 runtime 与签入 catalog 不一致时必须在第一笔链写前失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RUNTIME_ARTIFACT_DRIFT": "Sharing"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("runtime bytecode does not match", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)
            self._assert_no_reproducible_build_residue(fixture)

    def test_missing_sm_creation_artifact_blocks_first_deployment(self) -> None:
        """Console 未生成独立 SM creation 时不得回退使用 ECC 文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_MISSING_SM_BIN": "Sharing"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("sm compiler did not produce Sharing.bin", result.stdout + result.stderr)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)

    def test_symlink_console_artifact_blocks_first_deployment(self) -> None:
        """Console 输出使用符号链接时不得把外部目标当作本次独立编译证据。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_SYMLINK_ECC_BIN": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ecc compiler did not produce Storage.bin", result.stdout + result.stderr)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)

    def test_symlink_console_source_target_is_rejected_without_following(self) -> None:
        """源码 staging 不得跟随 Console 中预置的目标 symlink 覆盖其外部文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            source_directory = fixture["console_dir"] / "contracts/solidity"
            source_directory.mkdir(parents=True)
            symlink_target = fixture["root"] / "must-not-change.sol"
            symlink_target.write_text("preserve-me\n", encoding="utf-8")
            (source_directory / "Storage.sol").symlink_to(symlink_target)

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("must not be a symlink", result.stdout + result.stderr)
            self.assertEqual(
                symlink_target.read_text(encoding="utf-8"),
                "preserve-me\n",
            )
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)

    def test_directory_source_target_cleans_private_staging_file(self) -> None:
        """源码目标为目录时不得触发 mv 容器语义或遗留嵌套 staging 文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            invalid_target = fixture["console_dir"] / "contracts/solidity/Storage.sol"
            invalid_target.mkdir(parents=True)

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "source could not be atomically staged into FISCO Console",
                result.stdout + result.stderr,
            )
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)
            self._assert_no_staged_source_residue(fixture)

    def test_source_directory_swap_does_not_redirect_staging_cleanup(self) -> None:
        """源码目录被换成 symlink 时不得写入或删除重定向目录中的同名文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            external_directory = fixture["root"] / "external-source-directory"
            external_directory.mkdir()
            moved_symlink = fixture["root"] / "moved-source-directory-symlink"

            result = self._run_script(
                fixture,
                extra_env={
                    "FAKE_SWAP_SOURCE_DIRECTORY_BEFORE_POPULATE": "Storage",
                    "FAKE_SOURCE_SWAP_EXTERNAL": str(external_directory),
                    "FAKE_SOURCE_SWAP_LINK": str(moved_symlink),
                },
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "source could not be atomically staged into FISCO Console",
                result.stdout + result.stderr,
            )
            external_decoys = list(
                external_directory.glob(".record-platform-Storage.*"),
            )
            self.assertEqual(len(external_decoys), 1)
            self.assertEqual(
                external_decoys[0].read_text(encoding="utf-8"),
                "preserve-me\n",
            )
            self.assertTrue(moved_symlink.is_symlink())
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)
            self._assert_no_staged_source_residue(fixture)

    def test_staged_source_entry_swap_does_not_truncate_decoy(self) -> None:
        """私有 staging 目录项被替换时必须先核对 inode，禁止截断替代文件。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_staging = fixture["root"] / "original-staged-source"
            preserved_decoy = fixture["root"] / "preserved-staged-source-decoy"

            result = self._run_script(
                fixture,
                extra_env={
                    "FAKE_SWAP_STAGED_SOURCE_FILE_BEFORE_POPULATE": "Storage",
                    "FAKE_STAGED_SOURCE_ORIGINAL": str(original_staging),
                    "FAKE_STAGED_SOURCE_DECOY": str(preserved_decoy),
                },
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "source could not be atomically staged into FISCO Console",
                result.stdout + result.stderr,
            )
            self.assertEqual(
                preserved_decoy.read_text(encoding="utf-8"),
                "preserve-me\n",
            )
            self.assertFalse(original_staging.exists())
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)
            self._assert_no_staged_source_residue(fixture)

    def test_symlink_console_sm_fallback_directory_is_rejected(self) -> None:
        """SM fallback 的父目录为 symlink 时也必须在编译和链写前失败关闭。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            sdk_directory = fixture["console_dir"] / "contracts/sdk"
            sdk_directory.mkdir(parents=True)
            external_sm_directory = fixture["root"] / "external-sm"
            external_sm_directory.mkdir()
            (sdk_directory / "sm").symlink_to(
                external_sm_directory,
                target_is_directory=True,
            )

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("must not be a symlink", result.stdout + result.stderr)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn("deploy ", calls)

    def test_verified_console_artifact_drift_blocks_chain_write(self) -> None:
        """Phase 3 后替换 Console creation 时必须在第一笔部署交易前重新阻断。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_TAMPER_CONSOLE_AFTER_VERIFY": "Storage"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn(
                "staged Console artifacts changed before deployment",
                result.stdout + result.stderr,
            )
            calls = fixture["call_log"].read_text(encoding="utf-8")
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
                "did not return one valid chain/group/crypto/VM tuple",
                result.stdout + result.stderr,
            )
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn("getGroupInfo", calls)
            self.assertNotIn("deploy ", calls)

    def test_wasm_group_is_rejected_before_compile_and_deployment(self) -> None:
        """EVM 制品部署工具不得在 FISCO WASM 群组上继续编译或链写。"""
        for mode in ("current", "legacy", "both"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                listener, port = self._start_tcp_probe()
                fixture = self._create_fixture(Path(directory), port=port)

                result = self._run_script(
                    fixture,
                    extra_env={"FAKE_VM_FIELD_MODE": mode, "FAKE_WASM": "true"},
                )
                listener.join(timeout=5)

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("WASM", result.stdout + result.stderr)
                self.assertFalse(fixture["compiler_log"].exists())
                calls = fixture["call_log"].read_text(encoding="utf-8")
                self.assertNotIn("deploy ", calls)

    def test_legacy_wasm_false_is_accepted(self) -> None:
        """Legacy Console metadata may use the boolean wasm alias."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_VM_FIELD_MODE": "legacy"},
            )
            listener.join(timeout=5)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_equal_wasm_aliases_are_accepted(self) -> None:
        """Current and legacy VM aliases may coexist only with equal booleans."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_VM_FIELD_MODE": "both"},
            )
            listener.join(timeout=5)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_conflicting_wasm_aliases_are_rejected_before_compile(self) -> None:
        """Conflicting current and legacy aliases must fail closed."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_VM_FIELD_MODE": "conflict"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("did not return one valid", result.stdout + result.stderr)
            self.assertFalse(fixture["compiler_log"].exists())
            self.assertNotIn(
                "deploy ",
                fixture["call_log"].read_text(encoding="utf-8"),
            )

    def test_wrong_type_wasm_alias_is_rejected_before_compile(self) -> None:
        """String VM metadata must not be coerced into a boolean."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_VM_FIELD_MODE": "wrong-type"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(fixture["compiler_log"].exists())

    def test_missing_or_unknown_vm_alias_is_rejected_before_compile(self) -> None:
        """Missing and unknown VM fields must not be inferred as EVM."""
        for mode in ("missing", "unknown"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                listener, port = self._start_tcp_probe()
                fixture = self._create_fixture(Path(directory), port=port)

                result = self._run_script(
                    fixture,
                    extra_env={"FAKE_VM_FIELD_MODE": mode},
                )
                listener.join(timeout=5)

                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(fixture["compiler_log"].exists())

    def test_legacy_console_launcher_is_used_only_when_start_is_absent(self) -> None:
        """A legacy interactive console.sh remains a deterministic fallback."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            launcher_source = fixture["start_launcher"].read_bytes()
            fixture["start_launcher"].unlink()
            fixture["legacy_launcher"].write_bytes(launcher_source)
            fixture["legacy_launcher"].chmod(0o755)

            result = self._run_script(fixture)
            listener.join(timeout=5)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            launcher_calls = fixture["launcher_log"].read_text(encoding="utf-8")
            self.assertNotIn("start.sh", launcher_calls)
            self.assertIn("console.sh", launcher_calls)

    def test_invalid_console_launchers_fail_before_any_console_command(self) -> None:
        """Unsafe, missing and non-executable launchers fail during preflight."""
        cases = ("symlink", "directory", "missing", "non-executable", "outside")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as directory:
                fixture = self._create_fixture(Path(directory), port=1)
                start_launcher = fixture["start_launcher"]
                arguments: tuple[str, ...] = ()
                if case == "symlink":
                    start_launcher.unlink()
                    start_launcher.symlink_to(fixture["legacy_launcher"])
                elif case == "directory":
                    start_launcher.unlink()
                    start_launcher.mkdir()
                elif case == "missing":
                    start_launcher.unlink()
                    fixture["legacy_launcher"].unlink()
                elif case == "non-executable":
                    start_launcher.chmod(0o644)
                else:
                    outside = fixture["root"] / "start.sh"
                    outside.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
                    outside.chmod(0o755)
                    arguments = ("--console-launcher", str(outside))

                result = self._run_script(fixture, *arguments)

                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(fixture["call_log"].exists())
                self.assertFalse(fixture["launcher_log"].exists())

    def test_ambiguous_console_launcher_overrides_are_rejected(self) -> None:
        """Conflicting CLI and environment launcher selections fail closed."""
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = self._create_fixture(Path(temporary_directory), port=1)

            result = self._run_script(
                fixture,
                "--console-launcher",
                "start.sh",
                extra_env={"FISCO_CONSOLE_LAUNCHER": "console.sh"},
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ambiguous", result.stdout + result.stderr)
            self.assertFalse(fixture["call_log"].exists())

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

    def test_arbitrary_nonempty_runtime_blocks_identity_and_activation(self) -> None:
        """任意非空 code 即使身份夹具正确，也必须在身份调用前被 runtime 锚点拒绝。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_RUNTIME_CODE_DRIFT": "Sharing"},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("runtime bytecode mismatch", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertIn(f"getCode {SHARING_ADDRESS}", calls)
            self.assertNotIn(
                f"call Sharing {SHARING_ADDRESS} contractIdentity",
                calls,
            )

    def test_ambiguous_runtime_output_blocks_identity_and_activation(self) -> None:
        """同一次 getCode 输出出现两个不同候选值时必须失败关闭而非择长接受。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)
            original_env = fixture["env_file"].read_bytes()

            result = self._run_script(
                fixture,
                extra_env={"FAKE_AMBIGUOUS_CODE_ADDRESS": SHARING_ADDRESS},
            )
            listener.join(timeout=5)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("no verifiable runtime code", result.stdout + result.stderr)
            self.assertEqual(fixture["env_file"].read_bytes(), original_env)
            calls = fixture["call_log"].read_text(encoding="utf-8")
            self.assertNotIn(
                f"call Sharing {SHARING_ADDRESS} contractIdentity",
                calls,
            )

    def test_sm_group_selects_sm_creation_and_runtime_artifacts(self) -> None:
        """国密群组必须用同一 SM 变体完成部署前与链上 runtime 对账。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            listener, port = self._start_tcp_probe()
            fixture = self._create_fixture(Path(temporary_directory), port=port)

            result = self._run_script(
                fixture,
                extra_env={"FAKE_SM_CRYPTO_TYPE": "true"},
            )
            listener.join(timeout=5)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("crypto=sm", result.stdout)

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
        launcher_log = root / "console-launchers.log"
        compiler_log = root / "compiler-calls.log"
        artifact_compiler_log = root / "artifact-compiler-calls.log"
        date_log = root / "date-calls.log"
        receipt_dir = root / "receipts"
        temporary_build_root = root / "tmp"
        temporary_build_root.mkdir()
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

        for variant, crypto_directory in (("ecc", "keccak256"), ("sm", "sm3")):
            self._write_executable(
                root / f".fisco/solc/0.8.11/{crypto_directory}/solc",
                f"""#!/usr/bin/env bash
set -euo pipefail
variant="{variant}"
if [[ "$*" == "--version" ]]; then
    if [[ "$variant" == "sm" ]]; then
        printf 'solc, the solidity compiler commandline interface\n'
        printf 'Gm version: 0.8.11+commit.6b4cc280.Linux.g++\n'
    else
        printf 'solc, the solidity compiler commandline interface\n'
        printf 'Version: 0.8.11+commit.6b4cc280.Linux.g++\n'
    fi
    exit 0
fi
printf '%s %s\n' "$variant" "$*" >> "$FAKE_ARTIFACT_COMPILER_LOG"
output_dir=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -o) output_dir="$2"; shift 2 ;;
        *) shift ;;
    esac
done
mkdir -p "$output_dir"
for name in Storage Sharing; do
    cp "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/abi/$name.abi" \
        "$output_dir/$name.abi"
    cp "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/bin/$variant/$name.bin" \
        "$output_dir/$name.bin"
    cp "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/bin/runtime/$variant/$name.bin" \
        "$output_dir/$name.bin-runtime"
    if [[ "${{FAKE_ABI_DRIFT:-}}" == "$name" ]]; then
        printf '[{{"type":"function","name":"drifted","inputs":[]}}]\n' \
            > "$output_dir/$name.abi"
    fi
    if [[ "$variant" == "sm" && "${{FAKE_MISSING_SM_BIN:-}}" == "$name" ]]; then
        rm "$output_dir/$name.bin"
    fi
    if [[ "$variant" == "ecc" && "${{FAKE_SYMLINK_ECC_BIN:-}}" == "$name" ]]; then
        rm "$output_dir/$name.bin"
        ln -s "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/bin/ecc/$name.bin" \
            "$output_dir/$name.bin"
    fi
    if [[ "${{FAKE_RUNTIME_ARTIFACT_DRIFT:-}}" == "$name" ]]; then
        printf '60006000\n' > "$output_dir/$name.bin-runtime"
    fi
done
""",
            )
        source_commit = "6b4cc280eb884f1852e1ddf12aeea56a2103148c"
        fake_manifest = root / "fisco-solc-toolchains.json"
        machine = platform.machine().lower()
        if machine in {"amd64", "x64"}:
            machine = "x86_64"
        compiler_sources = {
            "ecc": root / ".fisco/solc/0.8.11/keccak256/solc",
            "sm": root / ".fisco/solc/0.8.11/sm3/solc",
        }
        compiler_shas = {
            variant: hashlib.sha256(path.read_bytes()).hexdigest()
            for variant, path in compiler_sources.items()
        }
        manifest = {
            "schemaVersion": "record-platform-fisco-solc-toolchains.v1",
            "sourceRepository": "https://example.invalid/solidity.git",
            "sourceCommit": source_commit,
            "solidityVersion": "0.8.11",
            "releaseBuild": {
                "prereleaseMarker": "empty",
                "commitHashMarker": source_commit,
                "cmakeArguments": [],
                "buildOrder": ["sm", "ecc"],
            },
            "supportedPlatforms": [{
                "system": platform.system().lower(),
                "machine": machine,
                "validatedBuilder": {},
            }],
            "variants": {
                "ecc": {
                    "buildGm": False,
                    "versionLine": "Version: 0.8.11+commit.6b4cc280.Linux.g++",
                    "validatedExecutableSha256": compiler_shas["ecc"],
                },
                "sm": {
                    "buildGm": True,
                    "versionLine": "Gm version: 0.8.11+commit.6b4cc280.Linux.g++",
                    "validatedExecutableSha256": compiler_shas["sm"],
                },
            },
        }
        fake_manifest.write_text(json.dumps(manifest), encoding="utf-8")
        manifest_sha = hashlib.sha256(fake_manifest.read_bytes()).hexdigest()
        solc_cache = root / "verified-solc-cache"
        release = solc_cache / source_commit
        for variant, source in compiler_sources.items():
            destination = release / variant / "solc"
            destination.parent.mkdir(parents=True)
            shutil.copyfile(source, destination)
            destination.chmod(0o700)
        (release / "provenance.json").write_text(
            json.dumps({
                "schemaVersion": "record-platform-fisco-solc-provenance.v1",
                "manifestSha256": manifest_sha,
                "sourceCommit": source_commit,
                "releasePrereleaseMarker": "empty",
                "sourceRepository": manifest["sourceRepository"],
                "platform": manifest["supportedPlatforms"][0],
                "variants": {
                    variant: {
                        "sha256": digest,
                        "buildGm": variant == "sm",
                        "versionLine": manifest["variants"][variant]["versionLine"],
                    }
                    for variant, digest in compiler_shas.items()
                },
            }),
            encoding="utf-8",
        )
        self._write_executable(
            console_dir / "start.sh",
            f"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$(basename "$0")" >> "$FAKE_LAUNCHER_LOG"
input="$(cat)"
printf '%s\n---\n' "$input" >> "$FAKE_CALL_LOG"
print_group_info() {{
    if [[ "${{FAKE_GROUP_INFO_MALFORMED:-}}" == "1" ]]; then
        printf '{{"chainID":"chain0"\n'
    else
        prefix='{{"chainID":"%s","groupID":"%s","smCryptoType":%s'
        case "${{FAKE_VM_FIELD_MODE:-current}}" in
            current)
                suffix=',"isWasm":%s,"nodeList":[]}}\n'
                ;;
            legacy)
                suffix=',"wasm":%s,"nodeList":[]}}\n'
                ;;
            both)
                suffix=',"isWasm":%s,"wasm":%s,"nodeList":[]}}\n'
                ;;
            conflict)
                suffix=',"isWasm":false,"wasm":true,"nodeList":[]}}\n'
                ;;
            wrong-type)
                suffix=',"isWasm":"false","nodeList":[]}}\n'
                ;;
            missing)
                suffix=',"nodeList":[]}}\n'
                ;;
            unknown)
                suffix=',"vmType":"EVM","nodeList":[]}}\n'
                ;;
            *)
                printf 'unsupported fake VM field mode\n' >&2
                return 2
                ;;
        esac
        printf "$prefix" "${{FAKE_CHAIN_ID:-chain0}}" \
            "${{FAKE_GROUP_ID:-group0}}" "${{FAKE_SM_CRYPTO_TYPE:-false}}"
        if [[ "${{FAKE_VM_FIELD_MODE:-current}}" == "both" ]]; then
            printf "$suffix" "${{FAKE_WASM:-false}}" "${{FAKE_WASM:-false}}"
        elif [[ "${{FAKE_VM_FIELD_MODE:-current}}" == "current" \
            || "${{FAKE_VM_FIELD_MODE:-current}}" == "legacy" ]]; then
            printf "$suffix" "${{FAKE_WASM:-false}}"
        else
            printf "$suffix"
        fi
    fi
}}
print_receipt() {{
    name="$1"
    default_transaction="$2"
    default_address="$3"
    default_block="$4"
    if [[ "$name" == "Storage" ]]; then
        prefix="FAKE_STORAGE_RECEIPT_"
    else
        prefix="FAKE_SHARING_RECEIPT_"
    fi
    status_key="${{prefix}}STATUS"
    transaction_key="${{prefix}}TRANSACTION"
    address_key="${{prefix}}ADDRESS"
    block_key="${{prefix}}BLOCK"
    status="${{!status_key:-0x0}}"
    transaction="${{!transaction_key:-$default_transaction}}"
    address="${{!address_key:-$default_address}}"
    block="${{!block_key:-$default_block}}"
    if [[ "$name" == "Sharing" && "${{FAKE_DUPLICATE_ADDRESS:-}}" == "1" ]]; then
        address="{STORAGE_ADDRESS}"
    fi
    if [[ "${{FAKE_RECEIPT_RPC_FAILURE:-}}" == "$name" ]]; then
        printf 'receipt RPC failed\n' >&2
        return 3
    fi
    if [[ "${{FAKE_RECEIPT_MISSING:-}}" == "$name" ]]; then
        printf '{{"jsonrpc":"2.0","result":null}}\n'
        return 0
    fi
    if [[ "${{FAKE_RECEIPT_MALFORMED:-}}" == "$name" ]]; then
        printf '{{"result":{{"status":"%s","transactionHash":"%s","blockNumber":"%s"}}}}\n' \
            "$status" "$transaction" "$block"
        return 0
    fi
    if [[ "${{FAKE_RECEIPT_JSON_RPC_ERROR:-}}" == "$name" ]]; then
        printf '{{"jsonrpc":"2.0","error":{{"code":-32000,"message":"failed"}}}}\n'
        return 0
    fi
    printf '{{"jsonrpc":"2.0","result":{{"status":"%s","transactionHash":"%s","contractAddress":"%s","blockNumber":"%s"}}}}\n' \
        "$status" "$transaction" "$address" "$block"
    if [[ "${{FAKE_RECEIPT_AMBIGUOUS:-}}" == "$name" ]]; then
        printf '{{"status":"0x0","transactionHash":"%s","contractAddress":"%s","blockNumber":"0x7f"}}\n' \
            "$transaction" "$address"
    fi
}}
case "$input" in
    *"getTransactionReceipt {STORAGE_TRANSACTION}"*)
        FAKE_CHAIN_ID="${{FAKE_RECEIPT_CHAIN_ID:-${{FAKE_CHAIN_ID:-chain0}}}}" \
            FAKE_GROUP_ID="${{FAKE_RECEIPT_GROUP_ID:-${{FAKE_GROUP_ID:-group0}}}}" \
            print_group_info
        print_receipt Storage "{STORAGE_TRANSACTION}" "{STORAGE_ADDRESS}" "0x10"
        ;;
    *"getTransactionReceipt {SHARING_TRANSACTION}"*)
        FAKE_CHAIN_ID="${{FAKE_RECEIPT_CHAIN_ID:-${{FAKE_CHAIN_ID:-chain0}}}}" \
            FAKE_GROUP_ID="${{FAKE_RECEIPT_GROUP_ID:-${{FAKE_GROUP_ID:-group0}}}}" \
            print_group_info
        print_receipt Sharing "{SHARING_TRANSACTION}" "{SHARING_ADDRESS}" "0x11"
        ;;
    *"getGroupInfo"*)
        print_group_info
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
    *"getCode "*)
        if [[ -n "${{FAKE_EMPTY_CODE_ADDRESS:-}}" \
            && "$input" == *"$FAKE_EMPTY_CODE_ADDRESS"* ]]; then
            printf '0x\n'
        elif [[ "${{FAKE_RUNTIME_CODE_DRIFT:-}}" == "Storage" \
            && "$input" == *"{STORAGE_ADDRESS}"* ]]; then
            printf '0x60006000\n'
        elif [[ "${{FAKE_RUNTIME_CODE_DRIFT:-}}" == "Sharing" \
            && "$input" == *"{SHARING_ADDRESS}"* ]]; then
            printf '0x60006000\n'
        else
            variant="ecc"
            if [[ "${{FAKE_SM_CRYPTO_TYPE:-false}}" == "true" ]]; then
                variant="sm"
            fi
            name="Sharing"
            if [[ "$input" == *"{STORAGE_ADDRESS}"* ]]; then
                name="Storage"
            fi
            printf '0x'
            tr -d '[:space:]' < \
                "$FAKE_PROJECT_ROOT/platform-fisco/src/main/resources/bin/runtime/$variant/$name.bin"
            printf '\n'
            if [[ -n "${{FAKE_AMBIGUOUS_CODE_ADDRESS:-}}" \
                && "$input" == *"$FAKE_AMBIGUOUS_CODE_ADDRESS"* ]]; then
                printf '{{"result":"0x60006000"}}\n'
            fi
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
            console_dir / "console.sh",
            """#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$(basename "$0")" >> "$FAKE_LAUNCHER_LOG"
if [[ $# -eq 0 ]]; then
    printf 'non-interactive console requires command arguments\n' >&2
    exit 2
fi
printf 'unsupported fake non-interactive command\n' >&2
exit 2
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
            bin_dir / "python3",
            """#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ge 9 \
    && "$1" == "-" \
    && -n "${FAKE_SWAP_SOURCE_DIRECTORY_BEFORE_POPULATE:-}" \
    && "$(basename "$2")" == "${FAKE_SWAP_SOURCE_DIRECTORY_BEFORE_POPULATE}.sol" \
    && "$6" == ".record-platform-${FAKE_SWAP_SOURCE_DIRECTORY_BEFORE_POPULATE}."* ]]; then
    source_directory="$3"
    original_directory="${source_directory}.record-platform-original"
    mv "$source_directory" "$original_directory"
    ln -s "$FAKE_SOURCE_SWAP_EXTERNAL" "$source_directory"
    printf 'preserve-me\n' > "$FAKE_SOURCE_SWAP_EXTERNAL/$6"
    set +e
    "$FAKE_REAL_PYTHON" "$@"
    status=$?
    set -e
    mv "$source_directory" "$FAKE_SOURCE_SWAP_LINK"
    mv "$original_directory" "$source_directory"
    exit "$status"
fi
if [[ $# -ge 9 \
    && "$1" == "-" \
    && -n "${FAKE_SWAP_STAGED_SOURCE_FILE_BEFORE_POPULATE:-}" \
    && "$(basename "$2")" == "${FAKE_SWAP_STAGED_SOURCE_FILE_BEFORE_POPULATE}.sol" \
    && "$6" == ".record-platform-${FAKE_SWAP_STAGED_SOURCE_FILE_BEFORE_POPULATE}."* ]]; then
    mv "$3/$6" "$FAKE_STAGED_SOURCE_ORIGINAL"
    printf 'preserve-me\n' > "$3/$6"
    set +e
    "$FAKE_REAL_PYTHON" "$@"
    status=$?
    set -e
    mv "$3/$6" "$FAKE_STAGED_SOURCE_DECOY"
    mv "$FAKE_STAGED_SOURCE_ORIGINAL" "$3/$6"
    exit "$status"
fi
if [[ "$*" == *"contracts/sdk/abi/"* \
    && -n "${FAKE_TAMPER_CONSOLE_AFTER_VERIFY:-}" ]]; then
    set +e
    "$FAKE_REAL_PYTHON" "$@"
    status=$?
    set -e
    if [[ $status -eq 0 ]]; then
        name="$FAKE_TAMPER_CONSOLE_AFTER_VERIFY"
        printf '60006000\n' > "$FAKE_CONSOLE_DIR/contracts/sdk/bin/$name.bin"
    fi
    exit "$status"
fi
if [[ "$*" == *"contract_fingerprint.py verify"* ]]; then
    count=0
    if [[ -f "$FAKE_PYTHON_STATE" ]]; then
        count="$(<"$FAKE_PYTHON_STATE")"
    fi
    count=$((count + 1))
    printf '%s\n' "$count" > "$FAKE_PYTHON_STATE"
    set +e
    "$FAKE_REAL_PYTHON" "$@"
    status=$?
    set -e
    exit "$status"
fi
exec "$FAKE_REAL_PYTHON" "$@"
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
            "launcher_log": launcher_log,
            "start_launcher": console_dir / "start.sh",
            "legacy_launcher": console_dir / "console.sh",
            "compiler_log": compiler_log,
            "artifact_compiler_log": artifact_compiler_log,
            "date_log": date_log,
            "receipt_dir": receipt_dir,
            "temporary_build_root": temporary_build_root,
            "solc_manifest": fake_manifest,
            "solc_cache": solc_cache,
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
            "FAKE_LAUNCHER_LOG": str(fixture["launcher_log"]),
            "FAKE_COMPILER_LOG": str(fixture["compiler_log"]),
            "FAKE_ARTIFACT_COMPILER_LOG": str(fixture["artifact_compiler_log"]),
            "FAKE_DATE_LOG": str(fixture["date_log"]),
            "FAKE_REAL_PYTHON": sys.executable,
            "FAKE_PYTHON_STATE": str(fixture["root"] / "python-state"),
            "FAKE_CONSOLE_DIR": str(fixture["console_dir"]),
            "TMPDIR": str(fixture["temporary_build_root"]),
            "FISCO_SOLC_TOOLCHAIN_MANIFEST": str(fixture["solc_manifest"]),
            "FISCO_SOLC_CACHE_DIR": str(fixture["solc_cache"]),
            "HOME": str(fixture["root"]),
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

    def _assert_no_reproducible_build_residue(
        self,
        fixture: dict[str, Path],
    ) -> None:
        """断言成功或失败退出后均未遗留可复现编译临时目录。"""
        self.assertEqual(
            list(fixture["temporary_build_root"].glob(
                "record-platform-contract-build.*",
            )),
            [],
        )

    def _assert_no_staged_source_residue(
        self,
        fixture: dict[str, Path],
    ) -> None:
        """断言成功或 staging 失败退出后均未遗留 Console 私有源码临时文件。"""
        source_directory = fixture["console_dir"] / "contracts/solidity"
        if not source_directory.exists():
            return
        self.assertEqual(
            list(source_directory.rglob(".record-platform-*")),
            [],
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
        path.parent.mkdir(parents=True, exist_ok=True)
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

    def test_missing_deployment_triplet_fails(self) -> None:
        """完整空缺的 legacy 证据也必须失败，避免 registry 无回执启动。"""
        result = self._run_contract_check({
            "FISCO_STORAGE_DEPLOYMENT_TX": "",
            "FISCO_STORAGE_DEPLOYMENT_BLOCK": "",
            "FISCO_STORAGE_DEPLOYMENT_EFFECTIVE_AT": "",
        })

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "Storage deployment evidence must set",
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
