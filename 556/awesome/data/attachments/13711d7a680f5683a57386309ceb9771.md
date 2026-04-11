## POST http://localhost:44341/echo → 200 OK

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
date: Sat, 11 Apr 2026 12:15:08 GMT
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
curl 'http://localhost:44341/echo' \
  -X POST \
  -d '{
  "name": "Eve",
  "action": "create"
}'
```
