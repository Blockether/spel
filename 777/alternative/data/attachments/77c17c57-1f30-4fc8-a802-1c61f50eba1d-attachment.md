## POST http://localhost:46225/echo → 200 OK

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
date: Sat, 03 Oct 2026 16:06:21 GMT
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
curl 'http://localhost:46225/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
