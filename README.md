# M3R Wallet Server - Spring Boot

A complete migration of the M3R Wallet Node.js server to **Spring Boot with MySQL** backend, featuring a secure Admin Dashboard accessible only from localhost.

For the full system document, including the Android app and the complete 3-node local runbook, see the project-level [`../README.md`](../README.md).

## Features

✅ **RESTful API** - Complete wallet transaction endpoints  
✅ **MySQL Database** - Persistent account, transaction, and escrow storage  
✅ **Admin Dashboard** - Beautiful web interface for network management  
✅ **Localhost-Only Access** - Admin dashboard restricted to 127.0.0.1  
✅ **Multi-Network Support** - Mainnet, Testnet, and Legacy networks  
✅ **Transaction Management** - Support for transfers, escrows, releases, and refunds  
✅ **2/3 Node Consensus** - Optional peer validation before transaction execution  
✅ **Rate Limiting** - Built-in protection against abuse  
✅ **Crypto Operations** - Keccak256, Base58 encoding, and ECDSA verification  

## Prerequisites

- **Java 17+** (JDK)
- **Maven 3.8+**
- **MySQL 8.0+**
- **Spring Boot 3.2.0**

## Installation

### 1. Set Up MySQL Database

```bash
# Start MySQL service
mysql -u root -p

# The application will automatically create the database
# Configure in application.yml or environment variables
```

### 2. Configure Environment Variables

Create a `.env` file or export variables:

```bash
export DB_HOST=localhost
export DB_USER=root
export DB_PASSWORD=your_password
export DB_NAME=m3rwallet_db
export SERVER_PORT=3000
export CORS_ORIGINS=http://localhost:3000
```

### 3. Build the Project

```bash
cd /home/noob_coder/Desktop/M3R_Coin/Node_Software
mvn clean install
```

### 4. Run the Application

```bash
mvn spring-boot:run
```

Or:

```bash
java -jar target/m3r-wallet-server-1.0.0.jar
```

The server will start on `http://localhost:3000`

## API Endpoints

### Public Endpoints

```
GET  /{network}/fee                    - Get fee policy
GET  /{network}/account                - Get account information
GET  /{network}/tx/status              - Get transaction status
POST /{network}/tx/submit              - Submit transaction
POST /{network}/arbiter/request        - Request arbiter
GET  /{network}/arbiter/list           - List arbiters
POST /{network}/faucet                 - Request testnet funds (testnet/legacy only)
GET  /{network}/health                 - Health check
```

### Internal Node Consensus Endpoints

These are called by peer nodes when `app.consensus.enabled=true`.

```
POST /{network}/node/tx/validate       - Validate without changing database state
POST /{network}/node/tx/execute        - Execute after quorum is reached
```

### Running 3 Local Consensus Nodes

Run each node with its own MySQL database, the same genesis data, and the other two node URLs in `app.consensus.peers`. A submit to any node validates locally, asks peers to validate, requires at least 2/3 yes votes, then broadcasts execute.

```bash
java -jar target/m3r-wallet-server-1.0.0.jar \
  --server.port=3000 \
  --spring.datasource.url=jdbc:mysql://localhost:3306/m3rwallet_node_3000?allowPublicKeyRetrieval=true\&useSSL=false\&serverTimezone=UTC \
  --app.consensus.enabled=true \
  --app.consensus.peers[0]=http://localhost:4000 \
  --app.consensus.peers[1]=http://localhost:5000 \
  --app.consensus.shared-secret=change-this-secret

java -jar target/m3r-wallet-server-1.0.0.jar \
  --server.port=4000 \
  --spring.datasource.url=jdbc:mysql://localhost:3306/m3rwallet_node_4000?allowPublicKeyRetrieval=true\&useSSL=false\&serverTimezone=UTC \
  --app.consensus.enabled=true \
  --app.consensus.peers[0]=http://localhost:3000 \
  --app.consensus.peers[1]=http://localhost:5000 \
  --app.consensus.shared-secret=change-this-secret

java -jar target/m3r-wallet-server-1.0.0.jar \
  --server.port=5000 \
  --spring.datasource.url=jdbc:mysql://localhost:3306/m3rwallet_node_5000?allowPublicKeyRetrieval=true\&useSSL=false\&serverTimezone=UTC \
  --app.consensus.enabled=true \
  --app.consensus.peers[0]=http://localhost:3000 \
  --app.consensus.peers[1]=http://localhost:4000 \
  --app.consensus.shared-secret=change-this-secret
```

For 5 nodes, each node should list the other 4 peers. The quorum becomes 4 yes votes out of 5.

### Admin Dashboard (Localhost Only)

```
GET  /admin                            - Dashboard homepage
GET  /admin/accounts                   - View all accounts
GET  /admin/transactions               - View all transactions
GET  /admin/escrows                    - View all escrows
GET  /admin/api/stats                  - API stats (JSON response)
```

## Usage Examples

### Get Account Balance

```bash
curl http://localhost:3000/mainnet/account?addr=0x1234567890abcdef
```

Response:
```json
{
  "status": "OK",
  "balance": "1000000",
  "nonce": 5
}
```

### Submit Transaction

```bash
curl -X POST http://localhost:3000/mainnet/tx/submit \
  -H "Content-Type: application/json" \
  -d '{
    "rawTxHex": "...",
    "pubKeyCompressedHex": "..."
  }'
```

### Access Admin Dashboard

Open in browser (from localhost only):
```
http://localhost:3000/admin
```

## Project Structure

```
Node_Software/
├── pom.xml                           # Maven configuration
├── src/main/
│   ├── java/com/m3rwallet/
│   │   ├── M3RWalletServerApplication.java
│   │   ├── config/                   # Spring configurations
│   │   │   ├── BeanConfig.java
│   │   │   ├── WebMvcConfig.java
│   │   │   └── LocalhostOnlyInterceptor.java
│   │   ├── controller/               # REST controllers
│   │   │   ├── WalletController.java
│   │   │   └── AdminDashboardController.java
│   │   ├── service/                  # Business logic
│   │   │   ├── WalletService.java
│   │   │   ├── AccountService.java
│   │   │   ├── TransactionService.java
│   │   │   └── EscrowService.java
│   │   ├── entity/                   # JPA entities
│   │   │   ├── Account.java
│   │   │   ├── Transaction.java
│   │   │   └── Escrow.java
│   │   ├── repository/               # Data access
│   │   │   ├── AccountRepository.java
│   │   │   ├── TransactionRepository.java
│   │   │   └── EscrowRepository.java
│   │   ├── dto/                      # Data transfer objects
│   │   │   └── ... (various DTOs)
│   │   └── util/                     # Utilities
│   │       └── AddressUtil.java
│   └── resources/
│       ├── application.yml           # Configuration
│       └── templates/admin/          # Thymeleaf templates
│           ├── dashboard.html
│           ├── accounts.html
│           ├── transactions.html
│           └── escrows.html
└── README.md
```

## Security Features

1. **Localhost-Only Admin Dashboard**: Interceptor checks `X-Forwarded-For` and direct IPs
2. **CORS Configuration**: Restricted origins to prevent unauthorized access
3. **Rate Limiting**: Built-in throttling on sensitive endpoints
4. **Sensitive Data Masking**: Logs mask private keys and mnemonics
5. **Database Transactions**: Atomic operations prevent race conditions
6. **SQL Injection Protection**: Parameterized queries throughout

## Configuration Options

Edit `application.yml` to customize:

```yaml
app:
  broadcast-fee: 100                  # Transaction fee in smallest units
  percent-fee-bps: 100               # Fee percentage in basis points
  genesis-address: NQWWb4huPaqUncdJHFm2FHFjTo1qqLskuq
  faucet:
    max-amount: 100000               # Max faucet amount per request
    rate-limit-per-hour: 5           # Max faucet requests per hour
```

## Troubleshooting

### MySQL Connection Failed

```bash
# Check MySQL is running
mysql -u root -p -e "SELECT 1"

# Verify database created
mysql -u root -p -e "SHOW DATABASES LIKE 'm3rwallet*'"
```

### Admin Dashboard Not Accessible

- Ensure you're accessing from `127.0.0.1` or `localhost`
- Check browser console for errors
- Verify Thymeleaf templates are in `src/main/resources/templates/`

### Port Already in Use

```bash
# Change port in application.yml or environment
export SERVER_PORT=3001
mvn spring-boot:run
```

## Converting from Node.js

If migrating from the original Node.js server:

1. **Stop Node.js server** and backup database
2. **Export MySQL data** (if using same DB)
3. **Update CORS_ORIGINS** environment variable
4. **Run Spring Boot** on same or different port
5. **Update client endpoints** (if URLs changed)

## Performance Tips

- Use connection pooling (configured in `application.yml`)
- Enable query caching for frequently accessed accounts
- Monitor transaction throughput with `/admin/api/stats`
- Use database indexes (automatically created)

## Development

### Hot Reload

```bash
mvn spring-boot:run
# Changes to Java files will auto-reload
```

### Debug Mode

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--debug"
```

### Run Tests

```bash
mvn test
```

## Deployment

### Docker

```dockerfile
FROM openjdk:17-jdk-slim
COPY target/m3r-wallet-server-1.0.0.jar app.jar
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
```

Build and run:
```bash
docker build -t m3r-wallet-server .
docker run -p 3000:3000 -e DB_HOST=host.docker.internal m3r-wallet-server
```

### Production Checklist

- [ ] Enable HTTPS with valid SSL certificate
- [ ] Set strong DB passwords
- [ ] Configure firewall to restrict admin access
- [ ] Set up monitoring and logging
- [ ] Enable database backups
- [ ] Use environment variables for secrets
- [ ] Test rate limiting under load
- [ ] Verify CORS policy is appropriate

## Support

For issues or feature requests, refer to the main M3RWallet repository.

## License

ISC License - Same as original Node.js project

---

**Happy wallet serving!** 🚀
