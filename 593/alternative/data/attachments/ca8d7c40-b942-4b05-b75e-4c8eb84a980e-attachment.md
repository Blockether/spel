## POST http://localhost:45957/echo → 200 OK

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
date: Sun, 12 Apr 2026 19:26:31 GMT
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
curl 'http://localhost:45957/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
