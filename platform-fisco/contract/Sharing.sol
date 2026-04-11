// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.11;

import "./Storage.sol";

// Slither timestamp warning is intentionally disabled for this contract because
// share expiry is an explicit time-based business rule and not a randomness source.
// slither-disable-start timestamp
contract Sharing is Storage {
    // 分享信息结构体
    struct ShareInfo {
        string uploader;        // 分享者
        bytes32[] fileHashes;   // 分享的文件哈希列表
        uint256 expireTime;     // 过期时间（时间戳，毫秒）
        bool isValid;           // 是否有效
    }

    // 字符集，用于生成分享码
    string private constant CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    // 分享码到分享信息的映射
    mapping(string => ShareInfo) private shareInfos;

    // 用户分享码列表索引：uploader → shareCode[]
    mapping(string => string[]) private userShareCodes;

    // 用于生成随机数的nonce
    uint256 private nonce = 0;

    // 分享事件
    event FileShared(string shareCode, string uploader, bytes32[] fileHashes, uint256 expireTime);

    // 取消分享事件
    event ShareCancelled(string shareCode, string uploader);
    
    // 生成分享码（基于合约上下文和递增 nonce，避免外部自调用依赖）
    function generateShareCode(
        string memory uploader,
        bytes32[] memory fileHashes,
        uint256 expireMinutes
    ) private returns (string memory) {
        bytes memory code = new bytes(6);
        uint256 charsetLength = bytes(CHARSET).length;
        bytes32 entropy = keccak256(
            abi.encode(address(this), uploader, fileHashes, expireMinutes, nonce)
        );
        uint256 seed = uint256(entropy);

        for(uint i = 0; i < 6; i++) {
            code[i] = bytes(CHARSET)[seed % charsetLength];
            seed /= charsetLength;
            if (seed == 0 && i < 5) {
                seed = uint256(keccak256(abi.encode(entropy, i)));
            }
        }
        
        // 增加nonce以确保下次生成不同的随机数
        nonce++;
        
        return string(code);
    }
    
    // 分享文件
    function shareFiles(
        string memory uploader,
        bytes32[] memory fileHashes,
        uint256 expireMinutes
    ) public returns (string memory) {
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        require(fileHashes.length > 0, "File hashes array cannot be empty");
        require(expireMinutes > 0, "Expire minutes must be greater than 0");
        require(expireMinutes <= 43200, "Expire minutes cannot exceed 30 days"); // 30天 = 43200分钟
        
        // 验证所有文件的所有权
        for(uint i = 0; i < fileHashes.length; i++) {
            bytes32 fileHash = fileHashes[i];
            requireFileExists(fileHash, "One of the files does not exist");
            requireFileOwner(fileHash, uploader, "Not owner of one of the files");
        }
        
        // 生成唯一的分享码
        string memory shareCode = generateShareCode(uploader, fileHashes, expireMinutes);
        while(shareInfos[shareCode].isValid) {
            shareCode = generateShareCode(uploader, fileHashes, expireMinutes);
        }
        
        // 计算过期时间（毫秒）
        uint256 expireTime = block.timestamp * 1000 + expireMinutes * 60 * 1000;
        
        // 存储分享信息
        shareInfos[shareCode] = ShareInfo({
            uploader: uploader,
            fileHashes: fileHashes,
            expireTime: expireTime,
            isValid: true
        });

        // 将分享码添加到用户分享列表
        userShareCodes[uploader].push(shareCode);

        // 触发事件
        emit FileShared(shareCode, uploader, fileHashes, expireTime);

        return shareCode;
    }
    
    // 获取分享文件信息（取消分享时返回 expireTime=-1，过期返回原过期时间且文件列表为空）
    function getSharedFiles(string memory shareCode) 
        public 
        view 
        returns (
            string memory uploader,
            File[] memory files,
            int256 expireTime
        ) 
    {
        require(bytes(shareCode).length == 6, "Invalid share code length");
        
        ShareInfo storage shareInfo = shareInfos[shareCode];
        require(bytes(shareInfo.uploader).length > 0, "Share code does not exist");

        if (!shareInfo.isValid) {
            return (shareInfo.uploader, new File[](0), -1);
        }

        uint256 nowMillis = block.timestamp * 1000;
        int256 signedExpireTime = int256(shareInfo.expireTime);
        if (shareInfo.expireTime <= nowMillis) {
            return (shareInfo.uploader, new File[](0), signedExpireTime);
        }
        
        // 获取所有分享的文件信息
        File[] memory sharedFiles = new File[](shareInfo.fileHashes.length);
        for(uint i = 0; i < shareInfo.fileHashes.length; i++) {
            bytes32 fileHash = shareInfo.fileHashes[i];
            requireFileExists(fileHash, "One of the shared files does not exist");
            requireFileOwner(fileHash, shareInfo.uploader, "Shared file owner mismatch");
            sharedFiles[i] = copyStoredFile(fileHash);
        }
        
        return (
            shareInfo.uploader,
            sharedFiles,
            signedExpireTime
        );
    }

    // 取消分享（需验证调用者身份）
    function cancelShare(string memory shareCode, string memory uploader) public {
        require(bytes(shareCode).length == 6, "Invalid share code length");
        require(bytes(uploader).length > 0, "Uploader cannot be empty");

        ShareInfo storage shareInfo = shareInfos[shareCode];
        require(shareInfo.isValid, "Share not found or already cancelled");
        require(keccak256(bytes(shareInfo.uploader)) == keccak256(bytes(uploader)), "Only uploader can cancel");

        // 设置分享为无效
        shareInfo.isValid = false;

        // 触发取消分享事件
        emit ShareCancelled(shareCode, shareInfo.uploader);
    }

    // 获取用户的所有分享码列表
    function getUserShareCodes(string memory uploader)
        public
        view
        returns (string[] memory)
    {
        return userShareCodes[uploader];
    }

    // 获取单个分享的详细信息（不校验有效性，用于查询）
    function getShareInfo(string memory shareCode)
        public
        view
        returns (
            string memory uploader,
            bytes32[] memory fileHashes,
            uint256 expireTime,
            bool isValid
        )
    {
        require(bytes(shareCode).length == 6, "Invalid share code length");

        ShareInfo storage shareInfo = shareInfos[shareCode];
        require(bytes(shareInfo.uploader).length > 0, "Share code does not exist");

        return (
            shareInfo.uploader,
            shareInfo.fileHashes,
            shareInfo.expireTime,
            shareInfo.isValid
        );
    }
}
// slither-disable-end timestamp
