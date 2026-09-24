## POST http://localhost:32963/echo → 200 OK

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
date: Thu, 24 Sep 2026 23:05:05 GMT
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
curl 'http://localhost:32963/echo' \
  -X POST \
  -H 'Authorization: Bearer test-token' \
  -d '{
  "action": "create"
}'
```
