-- Ensure the test user can read performance_schema.
-- This is executed by MySQL Docker image initialization scripts as root.
CREATE USER IF NOT EXISTS 'test'@'%' IDENTIFIED BY 'test';
GRANT SELECT ON performance_schema.* TO 'test'@'%';
FLUSH PRIVILEGES;
