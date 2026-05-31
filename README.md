# Telegram Channel Aggregator

Monitors Telegram channels, extracts product info via OpenAI, cleans affiliate URLs, and republishes to a destination channel.

## Quick Start

### 1. Start PostgreSQL

```bash
docker compose up -d postgres
```

### 2. Set environment variables

```bash
export TDLIB_API_ID=12345678
export TDLIB_API_HASH=your_api_hash_here
export OPENAI_API_KEY=sk-...
export TELEGRAM_BOT_TOKEN=123456:ABC-...
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/bot_aggregation
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
```

Get `TDLIB_API_ID` and `TDLIB_API_HASH` at https://my.telegram.org. Get `TELEGRAM_BOT_TOKEN` from [@BotFather](https://t.me/BotFather). The bot must be an **admin** in the destination channel.

### 3. Build and run

```bash
mvn clean package -DskipTests
java -jar target/bot-aggregation-1.0.0.jar
```

### 4. Set Telegram account

```bash
curl -X PUT http://localhost:8080/api/account \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "+1234567890"}'
```

### 5. Authenticate

Check auth state:

```bash
curl http://localhost:8080/api/auth/status
```

When state is `NEED_CODE`, submit the code Telegram sent you:

```bash
curl -X POST http://localhost:8080/api/auth/code \
  -H "Content-Type: application/json" \
  -d '{"code": "12345"}'
```

If 2FA is enabled (`NEED_PASSWORD`):

```bash
curl -X POST http://localhost:8080/api/auth/password \
  -H "Content-Type: application/json" \
  -d '{"password": "your_2fa_password"}'
```

Session persists in `./tdlib-data` — no re-auth needed on restart.

### 6. Set destination channel

```bash
curl -X PUT http://localhost:8080/api/destination \
  -H "Content-Type: application/json" \
  -d '{"channelId": -1001234567890}'
```

### 7. Add source channels

```bash
curl -X POST http://localhost:8080/api/channels \
  -H "Content-Type: application/json" \
  -d '{"channelId": -1009876543210}'
```

The UserBot account must be a member of the source channels. New messages are processed immediately.
