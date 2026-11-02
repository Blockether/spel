## POST http://localhost:35757/echo → 200 OK

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
date: Mon, 02 Nov 2026 20:02:29 GMT
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
curl 'http://localhost:35757/echo' \
  -X POST \
  -H 'Authorization: Bearer test-token' \
  -d '{
  "action": "create"
}'
```
