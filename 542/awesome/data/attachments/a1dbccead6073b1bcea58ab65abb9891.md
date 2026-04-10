## POST http://localhost:38057/echo → 200 OK

### Request Headers
```
POST http://localhost:38057/echo
```

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
date: Fri, 10 Apr 2026 03:53:03 GMT
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
curl 'http://localhost:38057/echo' \
  -X POST \
  -d '{
  "name": "Alice",
  "email": "alice@example.org"
}'
```
