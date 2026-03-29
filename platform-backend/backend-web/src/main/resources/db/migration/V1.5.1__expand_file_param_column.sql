-- Expand file_param column to TEXT to accommodate longer abi.encode output
-- from updated smart contracts (Storage.sol switched from abi.encodePacked to abi.encode)
ALTER TABLE `file` MODIFY COLUMN `file_param` TEXT COMMENT '区块链存证参数';
