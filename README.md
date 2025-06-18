# RecordPlatform

A comprehensive blockchain-based file storage and management platform that leverages FISCO BCOS blockchain technology for secure, immutable file storage with distributed storage capabilities.

## 🚀 Project Overview

RecordPlatform is an enterprise-grade file management system that combines blockchain technology with distributed storage to provide:

- **Blockchain-based File Storage**: Files are stored on FISCO BCOS blockchain for immutability and transparency
- **Distributed Storage**: Uses MinIO for scalable, distributed file storage with load balancing
- **Secure File Sharing**: Generate secure sharing codes with access control and expiration
- **Chunked Upload**: Support for large file uploads with resumable upload capabilities
- **User Management**: Complete user authentication and authorization system
- **Audit Trail**: Comprehensive operation logging and audit capabilities

## 🏗️ Architecture Overview

The platform follows a microservices architecture with the following key components:

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Frontend      │    │  Backend Web    │    │  Platform API   │
│   (External)    │◄──►│   (REST API)    │◄──►│   (Interfaces)  │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                ┌───────────────┼───────────────┐
                │               │               │
        ┌───────▼──────┐ ┌──────▼──────┐ ┌─────▼──────┐
        │ FISCO BCOS   │ │ MinIO       │ │ MySQL      │
        │ Blockchain   │ │ Storage     │ │ Database   │
        └──────────────┘ └─────────────┘ └────────────┘
```

### Core Modules

1. **platform-backend**: Main backend service with layered architecture
   - `backend-web`: REST API controllers and web configuration
   - `backend-service`: Business logic and service implementations
   - `backend-dao`: Data access layer with MyBatis Plus
   - `backend-common`: Shared utilities and common components
   - `backend-api`: Internal API definitions

2. **platform-fisco**: FISCO BCOS blockchain integration service
   - Smart contract management (Storage.sol, Sharing.sol)
   - Blockchain transaction handling
   - File hash generation and verification

3. **platform-minio**: Distributed storage service
   - MinIO client management with load balancing
   - File encryption and chunking
   - Storage monitoring and health checks

4. **platform-api**: Shared API interfaces and models
   - Dubbo service interfaces
   - Common data transfer objects
   - API documentation annotations

## ✨ Key Features

### File Management
- **Chunked Upload**: Large files are split into chunks for efficient upload
- **Resumable Upload**: Support for pausing and resuming file uploads
- **File Encryption**: Files are encrypted before storage for security
- **Duplicate Detection**: Automatic detection and handling of duplicate files
- **File Versioning**: Track file versions and changes

### Blockchain Integration
- **Immutable Storage**: File metadata stored on FISCO BCOS blockchain
- **Smart Contracts**: Custom Solidity contracts for file and sharing management
- **Transaction Tracking**: Complete audit trail of all file operations
- **Hash Verification**: Cryptographic verification of file integrity

### Distributed Storage
- **MinIO Integration**: Scalable object storage with multiple node support
- **Load Balancing**: Automatic distribution across storage nodes
- **Health Monitoring**: Real-time monitoring of storage node health
- **Dynamic Configuration**: Nacos-based configuration management

### Security & Access Control
- **JWT Authentication**: Secure token-based authentication
- **Role-based Access**: User roles and permissions management
- **Secure File Sharing**: Generate time-limited sharing codes
- **ID Obfuscation**: External ID mapping for security
- **Rate Limiting**: API rate limiting and flow control

### Monitoring & Audit
- **Operation Logging**: Comprehensive audit trail of all operations
- **Performance Monitoring**: Prometheus metrics integration
- **Health Checks**: Service health monitoring and alerting
- **Swagger Documentation**: Interactive API documentation

## 🛠️ Technology Stack

### Backend Framework
- **Spring Boot 3.2.11**: Main application framework
- **Java 21**: Programming language with preview features
- **Dubbo 3.3.3**: RPC framework for microservices communication
- **Nacos**: Service discovery and configuration management

### Database & Storage
- **MySQL**: Primary database for metadata storage
- **MyBatis Plus 3.5.9**: ORM framework with enhanced features
- **Redis**: Caching and session management
- **MinIO**: Distributed object storage

### Blockchain
- **FISCO BCOS 3.8.0**: Enterprise blockchain platform
- **Solidity ^0.8.11**: Smart contract programming language
- **Web3j 4.9.8**: Java library for blockchain interaction

### Security & Monitoring
- **Spring Security**: Authentication and authorization
- **JWT**: Token-based authentication
- **Prometheus**: Metrics collection and monitoring
- **Swagger/OpenAPI 3**: API documentation

### Message Queue & Communication
- **RabbitMQ**: Asynchronous message processing
- **Dubbo Triple Protocol**: High-performance RPC communication
- **Protobuf**: Efficient data serialization

## 📋 Prerequisites

Before setting up RecordPlatform, ensure you have the following installed:

### Required Software
- **Java 21** or higher
- **Maven 3.6+** for dependency management
- **MySQL 8.0+** for database storage
- **Redis 6.0+** for caching
- **RabbitMQ 3.8+** for message queuing

### Infrastructure Services
- **FISCO BCOS Node**: Blockchain network node
- **MinIO Server**: Object storage server
- **Nacos Server**: Service discovery and configuration center

### Development Tools (Optional)
- **IntelliJ IDEA** or **Eclipse** for development
- **Docker** for containerized deployment
- **Postman** for API testing

## 🚀 Quick Start

### 1. Clone the Repository
```bash
git clone https://github.com/your-username/RecordPlatform.git
cd RecordPlatform
```

### 2. Database Setup
```sql
-- Create database
CREATE DATABASE RecordPlatform CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Run migration scripts
mysql -u username -p RecordPlatform < platform-backend/db/migration/V1.0_Entity.sql
mysql -u username -p RecordPlatform < platform-backend/db/migration/V1.0_Audit.sql
mysql -u username -p RecordPlatform < platform-backend/db/migration/V1.0_Operation.sql
```

### 3. Configuration Setup

#### Environment Variables
Create environment configuration files or set the following variables:

```bash
# Database Configuration
export MYSQL_HOST=localhost
export MYSQL_PORT=3306
export MYSQL_USERNAME=your_username
export MYSQL_PASSWORD=your_password

# Redis Configuration
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_redis_password

# MinIO Configuration
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=your_access_key
export MINIO_SECRET_KEY=your_secret_key

# Nacos Configuration
export NACOS_SERVER=localhost:8848
export NACOS_USERNAME=nacos
export NACOS_PASSWORD=nacos
```

### 4. Build the Project
```bash
# Build all modules
mvn clean compile

# Package applications
mvn clean package -DskipTests
```

### 5. Start Services

#### Start Infrastructure Services
```bash
# Start MySQL, Redis, RabbitMQ, MinIO, and Nacos
# (Refer to their respective documentation for setup)

# Start FISCO BCOS node
# (Refer to FISCO BCOS documentation for node setup)
```

#### Start Application Services
```bash
# Start MinIO service
cd platform-minio
java -jar target/platform-minio-0.0.1-SNAPSHOT.jar

# Start FISCO BCOS service
cd platform-fisco
java -jar target/platform-fisco-0.0.1-SNAPSHOT.jar

# Start Backend Web service
cd platform-backend/backend-web
java -jar target/backend-web-0.0.1-SNAPSHOT.jar
```

### 6. Verify Installation
- **API Documentation**: http://localhost:8000/record-platform/swagger-ui.html
- **Health Check**: http://localhost:8000/record-platform/actuator/health
- **Druid Monitor**: http://localhost:8000/record-platform/druid/ (admin/123456)

## 📖 API Documentation

### Authentication Endpoints
```http
POST /api/auth/register          # User registration
POST /api/auth/ask-code          # Request verification code
POST /api/auth/reset-password    # Password reset
```

### File Management Endpoints
```http
GET    /api/file/list            # Get user files
POST   /api/file/uploader/start  # Start file upload
POST   /api/file/uploader/chunk  # Upload file chunk
POST   /api/file/complete        # Complete upload
GET    /api/file/download        # Download file
DELETE /api/file/delete          # Delete file
POST   /api/file/share           # Share files
GET    /api/file/getSharingFiles # Get shared files
```

### User Management Endpoints
```http
GET  /api/user/info              # Get user information
POST /api/user/modify-email      # Modify email
POST /api/user/change-password   # Change password
```

### Image Management Endpoints
```http
POST /api/image/upload/avatar    # Upload avatar
POST /api/image/upload/image     # Upload image
GET  /api/image/download/**      # Download image
```

## 🔧 Configuration Guide

### Database Configuration
```yaml
spring:
  datasource:
    druid:
      driver-class-name: com.mysql.cj.jdbc.Driver
      url: jdbc:mysql://localhost:3306/RecordPlatform?serverTimezone=Asia/Shanghai
      username: ${MYSQL_USERNAME}
      password: ${MYSQL_PASSWORD}
```

### FISCO BCOS Configuration
```yaml
bcos:
  network:
    peers[0]: 192.168.5.100:20200
system:
  groupId: group0
contract:
  storageAddress: '0xe7d7b002e51431b300ddc00b2c059ccc41ce4660'
  sharingAddress: '0x6d5de407db930fd7f5a0d9f19be7ec7bc7b36a2a'
```

### MinIO Configuration
```yaml
minio:
  nodes:
    - name: node1
      endpoint: http://192.168.5.100:9000
      accessKey: your_access_key
      secretKey: your_secret_key
```

### Dubbo Configuration
```yaml
dubbo:
  application:
    name: RecordPlatform_Main
  registry:
    address: nacos://localhost:8848
  protocol:
    name: tri
    port: 8090
```

## 🏗️ Development Guide

### Project Structure
```
RecordPlatform/
├── platform-api/                 # Shared API interfaces
│   └── src/main/java/cn/flying/platformapi/
│       ├── external/             # External service interfaces
│       └── response/             # Response models
├── platform-backend/            # Main backend service
│   ├── backend-api/             # Internal API definitions
│   ├── backend-common/          # Common utilities
│   ├── backend-dao/             # Data access layer
│   ├── backend-service/         # Business logic
│   ├── backend-web/             # Web controllers
│   └── db/migration/            # Database migration scripts
├── platform-fisco/             # Blockchain service
│   ├── contract/               # Smart contracts
│   └── src/main/java/cn/flying/fisco_bcos/
├── platform-minio/             # Storage service
│   └── src/main/java/cn/flying/minio/
├── uploads/                     # Temporary upload directory
├── processed/                   # Processed files directory
└── log/                        # Application logs
```

### Smart Contract Development

The platform uses two main smart contracts:

#### Storage Contract (`Storage.sol`)
```solidity
// Store file metadata on blockchain
function storeFile(
    string memory fileName,
    string memory uploader,
    string memory content,
    string memory param
) public returns (bytes32)

// Retrieve user files
function getUserFiles(string memory uploader)
    public view returns (FileInfo[] memory)

// Delete files
function deleteFile(string memory uploader, bytes32 fileHash)
    public returns (bool)
```

#### Sharing Contract (`Sharing.sol`)
```solidity
// Share files with access control
function shareFiles(
    string memory uploader,
    bytes32[] memory fileHashList,
    uint256 maxAccesses
) public returns (string memory)

// Access shared files
function getSharedFiles(string memory shareCode)
    public returns (ShareInfo memory)
```

### Adding New Features

#### 1. Add New API Endpoint
```java
@RestController
@RequestMapping("/api/your-feature")
public class YourFeatureController {

    @PostMapping("/action")
    @Operation(summary = "Your action description")
    public Result<String> yourAction(@RequestBody YourRequest request) {
        // Implementation
        return Result.success("Success");
    }
}
```

#### 2. Add New Service Interface
```java
@DubboService
public interface YourService {
    Result<String> performAction(String param);
}
```

#### 3. Implement Service
```java
@Service
public class YourServiceImpl implements YourService {

    @Override
    public Result<String> performAction(String param) {
        // Business logic implementation
        return Result.success("Action completed");
    }
}
```

### Testing

#### Unit Tests
```bash
# Run unit tests for specific module
mvn test -pl platform-backend/backend-service

# Run all tests
mvn test
```

#### Integration Tests
```bash
# Run integration tests
mvn verify -P integration-test
```

#### API Testing
Use the Swagger UI for interactive API testing:
- URL: http://localhost:8000/record-platform/swagger-ui.html
- Login: admin/123456

## 🚀 Deployment

### Docker Deployment

#### 1. Build Docker Images
```bash
# Build backend service
docker build -t record-platform-backend ./platform-backend

# Build FISCO service
docker build -t record-platform-fisco ./platform-fisco

# Build MinIO service
docker build -t record-platform-minio ./platform-minio
```

#### 2. Docker Compose Setup
```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: rootpassword
      MYSQL_DATABASE: RecordPlatform
    ports:
      - "3306:3306"

  redis:
    image: redis:6.2
    ports:
      - "6379:6379"

  nacos:
    image: nacos/nacos-server:v2.2.0
    environment:
      MODE: standalone
    ports:
      - "8848:8848"

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"

  record-platform:
    image: record-platform-backend
    depends_on:
      - mysql
      - redis
      - nacos
    ports:
      - "8000:8000"
    environment:
      SPRING_PROFILES_ACTIVE: prod
```

### Production Deployment

#### 1. Environment Setup
```bash
# Set production environment variables
export SPRING_PROFILES_ACTIVE=prod
export MYSQL_HOST=your-mysql-host
export REDIS_HOST=your-redis-host
export NACOS_SERVER=your-nacos-server
```

#### 2. Service Deployment
```bash
# Deploy services in order
java -jar platform-minio/target/platform-minio-0.0.1-SNAPSHOT.jar &
java -jar platform-fisco/target/platform-fisco-0.0.1-SNAPSHOT.jar &
java -jar platform-backend/backend-web/target/backend-web-0.0.1-SNAPSHOT.jar &
```

#### 3. Health Monitoring
```bash
# Check service health
curl http://localhost:8000/record-platform/actuator/health
curl http://localhost:8091/actuator/health  # FISCO service
curl http://localhost:8092/actuator/health  # MinIO service
```

## 🔧 Troubleshooting

### Common Issues

#### 1. Database Connection Issues
```bash
# Check MySQL connectivity
mysql -h localhost -u username -p RecordPlatform

# Verify database schema
SHOW TABLES;
```

#### 2. FISCO BCOS Connection Issues
```bash
# Check FISCO node status
curl -X POST --data '{"jsonrpc":"2.0","method":"getBlockNumber","params":[],"id":1}' \
  -H "Content-Type: application/json" http://localhost:8545
```

#### 3. MinIO Connection Issues
```bash
# Test MinIO connectivity
mc alias set local http://localhost:9000 ACCESS_KEY SECRET_KEY
mc ls local
```

#### 4. Service Discovery Issues
```bash
# Check Nacos service registration
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=RecordPlatform_Main
```

### Log Analysis

#### Application Logs
```bash
# View application logs
tail -f log/2025-05-07-spring-0.log

# Check specific service logs
docker logs record-platform-backend
```

#### Database Logs
```bash
# MySQL error log
tail -f /var/log/mysql/error.log

# Slow query log
tail -f /var/log/mysql/slow.log
```

## 🤝 Contributing

We welcome contributions to RecordPlatform! Please follow these guidelines:

### Development Workflow

1. **Fork the Repository**
   ```bash
   git fork https://github.com/your-username/RecordPlatform.git
   ```

2. **Create Feature Branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make Changes**
   - Follow Java coding standards
   - Add unit tests for new features
   - Update documentation as needed

4. **Test Changes**
   ```bash
   mvn clean test
   mvn verify
   ```

5. **Submit Pull Request**
   - Provide clear description of changes
   - Include test results
   - Reference related issues

### Code Style Guidelines

#### Java Code Style
- Use Java 21 features appropriately
- Follow Spring Boot best practices
- Use Lombok annotations for boilerplate code
- Implement proper error handling

#### Database Guidelines
- Use meaningful table and column names
- Add appropriate indexes
- Include migration scripts for schema changes

#### API Guidelines
- Use RESTful conventions
- Include comprehensive Swagger documentation
- Implement proper HTTP status codes
- Add request/response validation

### Testing Requirements

#### Unit Tests
- Minimum 80% code coverage
- Test all business logic
- Mock external dependencies

#### Integration Tests
- Test API endpoints
- Verify database operations
- Test service interactions

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 👥 Authors

- **flying** - *Initial work* - [GitHub Profile](https://github.com/flyingcoding)

## 🙏 Acknowledgments

- **FISCO BCOS Community** - For the excellent blockchain platform
- **Spring Boot Team** - For the robust application framework
- **MinIO Team** - For the scalable object storage solution
- **Apache Dubbo Community** - For the high-performance RPC framework

## 📞 Support

For support and questions:

- **GitHub Issues**: [Create an issue](https://github.com/wbq123789/RecordPlatform/issues)
- **Documentation**: Check the Swagger UI at `/swagger-ui.html`
- **Email**: Contact the development team

## 🔄 Changelog

### Version 0.0.1-SNAPSHOT (Current)
- Initial release with core functionality
- Blockchain-based file storage
- Distributed storage with MinIO
- User management and authentication
- File sharing capabilities
- Comprehensive API documentation

---

**Note**: This project is under active development. Please check the [GitHub repository](https://github.com/wbq123789/RecordPlatform) for the latest updates and releases.
