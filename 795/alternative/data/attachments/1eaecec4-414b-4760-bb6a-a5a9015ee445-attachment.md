## POST http://localhost:43315/echo → 200 OK

### Request Body
```json
{
  "name": "Eve",
  "action": "create"
}
```

### Response Headers
```
content-length: 72
content-type: application/json
date: Wed, 21 Oct 2026 17:51:04 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Eve",
    "action": "create"
  }
}
```

### cURL
```bash
curl 'http://localhost:43315/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
