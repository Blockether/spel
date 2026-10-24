## POST http://localhost:33411/echo → 200 OK

### Request Body
```json
{
  "name": "Alice",
  "email": "alice@example.org"
}
```

### Response Headers
```
content-length: 84
content-type: application/json
date: Sat, 24 Oct 2026 08:08:27 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Alice",
    "email": "alice@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:33411/echo' \
  -X POST \
  -d '{
  "name": "Alice",
  "email": "alice@example.org"
}'
```
