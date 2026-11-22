## POST http://localhost:37473/echo → 200 OK

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
date: Sun, 22 Nov 2026 19:46:40 GMT
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
curl 'http://localhost:37473/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
