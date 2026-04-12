## POST http://localhost:33879/echo → 200 OK

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
date: Sun, 12 Apr 2026 18:55:05 GMT
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
curl 'http://localhost:33879/echo' \
  -X POST \
  -d '{
  "name": "Alice",
  "email": "alice@example.org"
}'
```
