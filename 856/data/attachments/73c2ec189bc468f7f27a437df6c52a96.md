## POST http://localhost:34177/echo → 200 OK

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
date: Sun, 22 Nov 2026 18:41:48 GMT
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
curl 'http://localhost:34177/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
