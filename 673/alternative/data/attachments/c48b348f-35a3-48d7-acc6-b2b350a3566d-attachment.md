## POST http://localhost:42975/echo → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Request Body
```json
{
  "action": "create"
}
```

### Response Headers
```
content-length: 59
content-type: application/json
date: Sat, 01 Aug 2026 11:40:07 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:42975/echo' \
  -X POST \
  -H 'Authorization: Bearer test-token' \
  -d '{
  "action": "create"
}'
```
