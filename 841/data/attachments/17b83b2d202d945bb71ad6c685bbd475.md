## POST http://localhost:33573/echo → 200 OK

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
date: Sat, 21 Nov 2026 23:22:59 GMT
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
curl 'http://localhost:33573/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
