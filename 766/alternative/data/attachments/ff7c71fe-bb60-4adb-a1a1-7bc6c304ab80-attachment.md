## POST http://localhost:36027/echo → 200 OK

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
date: Sun, 27 Sep 2026 16:41:10 GMT
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
curl 'http://localhost:36027/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
