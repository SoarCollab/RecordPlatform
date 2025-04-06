// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.11;

import "./Storage.sol";

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
    
    // 用于生成随机数的nonce
    uint256 private nonce = 0;
    
    // 分享事件
    event FileShared(string shareCode, string uploader, bytes32[] fileHashes, uint256 expireTime);
    
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
            File memory file = this.getFile(uploader, fileHash);
            require(
                keccak256(abi.encodePacked(file.uploader)) == keccak256(abi.encodePacked(uploader)),
                "Not owner of one of the files"
            );
        }
        
        // 生成唯一的分享码
        string memory shareCode;
        bool isUnique = false;
        
        while(!isUnique) {
            shareCode = generateShareCode();
            if(!shareInfos[shareCode].isValid) {
                isUnique = true;
            }
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
        
        // 触发事件
        emit FileShared(shareCode, uploader, fileHashes, expireTime);
        
        return shareCode;
    }
    
    // 获取分享文件信息
    function getSharedFiles(string memory shareCode) 
        public 
        view 
        returns (
            string memory uploader,
            File[] memory files,
            uint256 expireTime
        ) 
    {
        require(bytes(shareCode).length == 6, "Invalid share code length");
        
        ShareInfo storage shareInfo = shareInfos[shareCode];
        require(shareInfo.isValid, "Share code does not exist");
        require(shareInfo.expireTime > block.timestamp * 1000, "Share has expired");
        
        // 获取所有分享的文件信息
        File[] memory sharedFiles = new File[](shareInfo.fileHashes.length);
        for(uint i = 0; i < shareInfo.fileHashes.length; i++) {
            sharedFiles[i] = this.getFile(shareInfo.uploader, shareInfo.fileHashes[i]);
        }
        
        return (
            shareInfo.uploader,
            sharedFiles,
            shareInfo.expireTime
        );
    }
}
