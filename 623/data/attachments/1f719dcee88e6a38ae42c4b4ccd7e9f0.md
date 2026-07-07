## POST http://localhost:40863/echo → 200 OK

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
date: Sun, 26 Apr 2026 20:01:13 GMT
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
curl 'http://localhost:40863/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
