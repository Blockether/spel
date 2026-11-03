## POST http://localhost:32893/echo → 200 OK

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
date: Tue, 03 Nov 2026 16:28:08 GMT
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
curl 'http://localhost:32893/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
