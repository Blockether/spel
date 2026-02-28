## POST /echo → 200 OK

### Request Headers
```
POST /echo
```

### Request Body
```json
{
  "name": "Alice",
  "email": "alice@example.com"
}
```

### Response Headers
```
content-length: 84
content-type: application/json
date: Sat, 28 Feb 2026 08:04:42 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Alice",
    "email": "alice@example.com"
  }
}
```

### cURL
```bash
curl '/echo' \
  -X POST \
  -d '{"name":"Alice","email":"alice@example.com"}'
```
