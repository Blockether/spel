## POST http://localhost:41803/echo → 200 OK

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
date: Fri, 11 Sep 2026 11:38:04 GMT
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
curl 'http://localhost:41803/echo' \
  -X POST \
  -d '{
  "name": "Alice",
  "email": "alice@example.org"
}'
```
