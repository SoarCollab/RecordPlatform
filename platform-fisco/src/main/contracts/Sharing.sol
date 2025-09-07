// SPDX-License-Identifier: MIT
pragma solidity ^0.8.11;

import "./Storage.sol";

contract Sharing is Storage {
    // 分享信息结构体
    struct ShareInfo {
        string uploader;        // 分享者
        bytes32[] fileHashes;   // 分享的文件哈希列表
        uint256 maxAccesses;    // 最大访问次数
        uint256 accessCount;    // 当前访问次数
    }

    // 字符集，用于生成分享码
    string private constant CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // 分享码到分享信息的映射
    mapping(string => ShareInfo) private shareInfos;

    // 用于生成随机数的nonce
    uint256 private nonce = 0;

    // 分享事件
    event FileShared(string shareCode, string uploader, bytes32[] fileHashes, uint256 maxAccesses);

    // 生成随机分享码
    function generateShareCode() private returns (string memory) {
        bytes memory code = new bytes(6);
        uint256 charsetLength = bytes(CHARSET).length;

        for(uint i = 0; i < 6; i++) {
            // 使用区块信息、nonce和循环索引来生成伪随机数
            uint256 randomIndex = uint256(
                keccak256(
                    abi.encodePacked(
                        block.timestamp,
                        block.difficulty,
                        msg.sender,
                        nonce,
                        i
                    )
                )
            ) % charsetLength;

            code[i] = bytes(CHARSET)[randomIndex];
        }

        // 增加nonce以确保下次生成不同的随机数
        nonce++;

        return string(code);
    }

    // 分享文件
    function shareFiles(
        string memory uploader,
        bytes32[] memory fileHashList,
        uint256 maxAccesses
    ) public returns (string memory) {
        require(maxAccesses > 0 && maxAccesses <= 100, "Invalid max accesses");

        // 验证所有文件的所有权
        for(uint i = 0; i < fileHashList.length; i++) {
            bytes32 fileHash = fileHashList[i];
            File memory file = this.getFile(uploader, fileHash);
            require(
                keccak256(abi.encodePacked(file.uploader)) == keccak256(abi.encodePacked(uploader)),
                "Not owner of one of the files"
            );
        }
        
        string memory shareCode = generateShareCode();

        // 存储分享信息
        shareInfos[shareCode] = ShareInfo({
            uploader: uploader,
            fileHashes: fileHashList,
            maxAccesses: maxAccesses,
            accessCount: 0
        });

        emit FileShared(shareCode, uploader, fileHashList, maxAccesses);
        return shareCode;
    }

    // 获取分享的文件
    function getSharedFiles(string memory shareCode) public returns (string memory, File[] memory) {
        ShareInfo storage sharedFiles = shareInfos[shareCode];
        require(bytes(sharedFiles.uploader).length != 0, "Share code does not exist");
        require(sharedFiles.accessCount < sharedFiles.maxAccesses, "Share has reached maximum access limit");
        
        // 增加访问计数
        sharedFiles.accessCount++;
        
        File[] memory files = new File[](sharedFiles.fileHashes.length);
        for (uint i = 0; i < sharedFiles.fileHashes.length; i++) {
            files[i] = this.getFile(sharedFiles.uploader, sharedFiles.fileHashes[i]);
        }

        return (
            sharedFiles.uploader,
            files
        );
    }
}
