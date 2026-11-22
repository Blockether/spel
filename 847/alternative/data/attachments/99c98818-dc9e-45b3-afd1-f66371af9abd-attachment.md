## POST http://localhost:38523/echo → 200 OK

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
date: Sun, 22 Nov 2026 13:33:17 GMT
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
curl 'http://localhost:38523/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
