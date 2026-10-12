## POST http://localhost:44029/echo → 200 OK

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
date: Mon, 12 Oct 2026 16:15:41 GMT
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
curl 'http://localhost:44029/echo' \
  -X POST \
  -H 'Authorization: Bearer test-token' \
  -d '{
  "action": "create"
}'
```
