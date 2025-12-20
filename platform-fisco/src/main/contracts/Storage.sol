// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.11;

contract Storage {
    // 文件结构体
    struct File {
        string fileName;
        string uploader;
        string content;
        string param;
        bytes32 fileHash;
        uint256 uploadTime;
    }

    // 文件信息结构体（用于返回文件列表）
    struct FileInfo {
        string fileName;
        bytes32 fileHash;
    }
    
    // 存储所有文件的映射：fileHash => File
    mapping(bytes32 => File) private files;
    
    // 用户存储的文件hash列表映射：uploader => fileHash[]
    mapping(string => bytes32[]) private userFiles;
    
    // 用户文件索引映射：uploader => fileHash => index
    mapping(string => mapping(bytes32 => uint256)) private userFileIndexes;
    
    // 存储文件事件
    event FileStored(string fileName, string uploader, bytes32 fileHash, uint256 uploadTime);
    
    // 删除文件事件
    event FileDeleted(string uploader, bytes32 fileHash);
    event FilesDeleted(string uploader, bytes32[] fileHashes);
    
    // 生成文件hash
    function generateFileHash(
        string memory fileName,
        string memory uploader,
        string memory content,
        string memory param,
        uint256 timestamp
    ) private pure returns (bytes32) {
        return keccak256(
            abi.encodePacked(fileName, uploader, content, param, timestamp)
        );
    }
    
    // 存储文件
    function storeFile(
        string memory fileName,
        string memory uploader,
        string memory content,
        string memory param
    ) public returns (bytes32) {
        require(bytes(fileName).length > 0, "File name cannot be empty");
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        require(bytes(content).length > 0, "File content cannot be empty");
        
        // 获取当前时间戳（毫秒）
        uint256 timestamp = block.timestamp * 1000;
        
        // 生成文件hash
        bytes32 fileHash = generateFileHash(fileName, uploader, content, param, timestamp);
        
        // 确保文件不存在
        require(bytes(files[fileHash].fileName).length == 0, "File already exists");
        
        // 存储文件信息
        files[fileHash] = File({
            fileName: fileName,
            uploader: uploader,
            content: content,
            param: param,
            fileHash: fileHash,
            uploadTime: timestamp
        });
        
        // 添加到用户文件列表并记录索引
        userFiles[uploader].push(fileHash);
        userFileIndexes[uploader][fileHash] = userFiles[uploader].length - 1;
        
        // 触发事件
        emit FileStored(fileName, uploader, fileHash, timestamp);
        
        return fileHash;
    }
    
    // 根据用户名查询所有文件
    function getUserFiles(string memory uploader) 
        public 
        view 
        returns (FileInfo[] memory) 
    {
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        bytes32[] storage userFileHashes = userFiles[uploader];
        FileInfo[] memory fileInfoList = new FileInfo[](userFileHashes.length);
        
        for (uint i = 0; i < userFileHashes.length; i++) {
            File storage file = files[userFileHashes[i]];
            fileInfoList[i] = FileInfo({
                fileName: file.fileName,
                fileHash: file.fileHash
            });
        }
        
        return fileInfoList;
    }
    
    // 根据用户名和文件hash查询特定文件
    function getFile(string memory uploader, bytes32 fileHash) 
        public 
        view 
        returns (File memory) 
    {
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        File storage file = files[fileHash];
        require(bytes(file.fileName).length > 0, "File does not exist");
        require(
            keccak256(abi.encodePacked(file.uploader)) == keccak256(abi.encodePacked(uploader)),
            "File does not belong to this user"
        );
        return file;
    }
    
    // 从用户文件列表中删除指定的文件哈希
    function removeFromUserFiles(string memory uploader, bytes32 fileHash) private {
        bytes32[] storage userFileHashes = userFiles[uploader];
        uint256 index = userFileIndexes[uploader][fileHash];
        uint256 lastIndex = userFileHashes.length - 1;
        
        if (index != lastIndex) {
            // 将最后一个元素移到要删除的位置
            bytes32 lastHash = userFileHashes[lastIndex];
            userFileHashes[index] = lastHash;
            userFileIndexes[uploader][lastHash] = index;
        }
        
        // 删除最后一个元素
        userFileHashes.pop();
        delete userFileIndexes[uploader][fileHash];
    }

    // 删除单个文件
    function deleteFile(string memory uploader, bytes32 fileHash) 
        public 
        returns (bool) 
    {
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        
        // 检查文件是否存在
        File storage file = files[fileHash];
        require(bytes(file.fileName).length > 0, "File does not exist");
        
        // 检查是否为文件所有者
        require(
            keccak256(abi.encodePacked(file.uploader)) == keccak256(abi.encodePacked(uploader)),
            "Not file owner"
        );
        
        // 从文件映射中删除
        delete files[fileHash];
        
        // 从用户文件列表中删除
        removeFromUserFiles(uploader, fileHash);
        
        // 触发事件
        emit FileDeleted(uploader, fileHash);
        
        return true;
    }
    
    // 批量删除文件
    function deleteFiles(string memory uploader, bytes32[] memory fileHashes) 
        public 
        returns (bool) 
    {
        require(bytes(uploader).length > 0, "Uploader name cannot be empty");
        require(fileHashes.length > 0, "File hashes array cannot be empty");
        
        for (uint i = 0; i < fileHashes.length; i++) {
            bytes32 fileHash = fileHashes[i];
            
            // 检查文件是否存在
            File storage file = files[fileHash];
            require(bytes(file.fileName).length > 0, "One of the files does not exist");
            
            // 检查是否为文件所有者
            require(
                keccak256(abi.encodePacked(file.uploader)) == keccak256(abi.encodePacked(uploader)),
                "Not owner of one of the files"
            );
            
            // 从文件映射中删除
            delete files[fileHash];
            
            // 从用户文件列表中删除
            removeFromUserFiles(uploader, fileHash);
        }
        
        // 触发事件
        emit FilesDeleted(uploader, fileHashes);
        
        return true;
    }
}
